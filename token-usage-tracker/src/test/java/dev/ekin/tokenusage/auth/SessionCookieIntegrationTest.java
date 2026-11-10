package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard authenticates with an httpOnly cookie; CLI clients keep using
 * the Bearer header. Writes authenticated by the cookie need the CSRF header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionCookieIntegrationTest {

	private static final String PASSWORD = "cookie-test-password";
	private static final String CSRF = JwtAuthenticationFilter.CSRF_HEADER;

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	MemberRepository memberRepository;
	@Autowired
	JwtService jwtService;

	Organization org;
	Member alice;
	Member bob;

	@BeforeEach
	void setUp() {
		String id = UUID.randomUUID().toString().substring(0, 8);
		org = organizationRepository.save(new Organization("Cookie Org " + id, "cookie-" + id));
		alice = memberRepository.save(new Member(org.getId(), null, "alice-" + id + "@test.dev", "Alice",
				passwordEncoder.encode(PASSWORD), "MEMBER"));
		bob = memberRepository.save(new Member(org.getId(), null, "bob-" + id + "@test.dev", "Bob",
				passwordEncoder.encode(PASSWORD), "MEMBER"));
	}

	@AfterEach
	void tearDown() {
		memberRepository.delete(alice);
		memberRepository.delete(bob);
		organizationRepository.delete(org);
	}

	private MvcResult login(Member member) throws Exception {
		return mockMvc.perform(post("/auth/token").contentType("application/json")
						.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(member.getEmail(), PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();
	}

	private Cookie sessionCookie(Member member) throws Exception {
		Cookie cookie = login(member).getResponse().getCookie("tut_session");
		assertThat(cookie).isNotNull();
		return cookie;
	}

	@Test
	void loginSetsAHardenedSessionCookieAndStillReturnsTheToken() throws Exception {
		MvcResult result = login(alice);

		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie)
				.startsWith("tut_session=")
				.contains("Path=/", "Max-Age=" + jwtService.ttlSeconds(), "HttpOnly", "Secure", "SameSite=Strict");
		JsonNode body = objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class);
		String token = body.get("token").asString();
		assertThat(token).isNotBlank();
		assertThat(setCookie).startsWith("tut_session=" + token + ";");
	}

	@Test
	void theCookieAloneAuthenticatesReads() throws Exception {
		mockMvc.perform(get("/auth/me").cookie(sessionCookie(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(alice.getEmail()));
	}

	@Test
	void bearerHeaderWinsOverTheCookie() throws Exception {
		mockMvc.perform(get("/auth/me").cookie(sessionCookie(alice))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueToken(bob)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(bob.getEmail()));
	}

	@Test
	void invalidOrEmptyCookieIsUnauthenticated() throws Exception {
		mockMvc.perform(get("/auth/me").cookie(new Cookie("tut_session", "not-a-jwt")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/auth/me").cookie(new Cookie("tut_session", "")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void cookieAuthenticatedWriteWithoutTheCsrfHeaderIs403() throws Exception {
		mockMvc.perform(post("/usage").cookie(sessionCookie(alice)).contentType("application/json").content("{}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.detail")
						.value("Cookie-authenticated requests that change data need the X-Requested-With header"));
	}

	@Test
	void cookieAuthenticatedWriteWithTheCsrfHeaderPassesTheGate() throws Exception {
		// An empty body fails validation: 400 proves the request got past the CSRF check
		mockMvc.perform(post("/usage").cookie(sessionCookie(alice)).header(CSRF, "XMLHttpRequest")
						.contentType("application/json").content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void bearerWritesNeedNoCsrfHeader() throws Exception {
		mockMvc.perform(post("/usage").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueToken(alice))
						.contentType("application/json").content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void logoutClearsTheCookie() throws Exception {
		String setCookie = mockMvc.perform(post("/auth/logout").cookie(sessionCookie(alice)).header(CSRF, "XMLHttpRequest"))
				.andExpect(status().isNoContent())
				.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

		assertThat(setCookie).startsWith("tut_session=;").contains("Max-Age=0", "HttpOnly", "Path=/");
	}

	@Test
	void logoutWithoutASessionStillSucceeds() throws Exception {
		mockMvc.perform(post("/auth/logout")).andExpect(status().isNoContent());
	}

}
