package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lockout on POST /auth/token against real Redis: 5 failures per email and
 * 20 per client address within the window, then 429 with Retry-After.
 *
 * <p>Every test uses its own email and client address, and the counters are
 * deleted afterwards, because other test classes share this Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginRateLimitIntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	StringRedisTemplate redisTemplate;
	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	MemberRepository memberRepository;

	String email;
	String otherEmail;
	String ip;
	Organization org;

	@BeforeEach
	void setUp() {
		String id = UUID.randomUUID().toString().substring(0, 8);
		email = "limit-" + id + "@test.dev";
		otherEmail = "other-" + id + "@test.dev";
		// TEST-NET-3 (documentation range), unique per test
		ip = "203.0.113." + (1 + Math.floorMod(id.hashCode(), 250));
		org = organizationRepository.save(new Organization("Limit Org " + id, "limit-org-" + id));
		for (String address : new String[] {email, otherEmail}) {
			memberRepository.save(new Member(org.getId(), null, address, "Limit",
					passwordEncoder.encode(PASSWORD), "MEMBER"));
		}
	}

	@AfterEach
	void tearDown() {
		Set<String> keys = redisTemplate.keys("login:fail:*");
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
		memberRepository.findByEmail(email).ifPresent(memberRepository::delete);
		memberRepository.findByEmail(otherEmail).ifPresent(memberRepository::delete);
		organizationRepository.delete(org);
	}

	private ResultActions login(String address, String password, String fromIp) throws Exception {
		return mockMvc.perform(post("/auth/token")
				.with(request -> {
					request.setRemoteAddr(fromIp);
					return request;
				})
				.contentType("application/json")
				.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(address, password)));
	}

	@Test
	void sixthAttemptAfterFiveFailuresIs429EvenWithTheCorrectPassword() throws Exception {
		for (int i = 0; i < 5; i++) {
			login(email, "wrong-" + i, ip).andExpect(status().isUnauthorized());
		}

		String retryAfter = login(email, PASSWORD, ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.detail").value("Too many failed login attempts; try again later"))
				.andReturn().getResponse().getHeader("Retry-After");
		assertThat(Long.parseLong(retryAfter)).isBetween(1L, 900L);
	}

	@Test
	void theCounterExpiresAndLoginWorksAgainOnceItIsGone() throws Exception {
		for (int i = 0; i < 5; i++) {
			login(email, "wrong", ip);
		}
		String key = LoginRateLimiter.emailKey(email);
		// The lockout ends by itself: the counter carries the window as its expiry
		assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 900L);

		redisTemplate.delete(key); // what Redis does when the window has passed
		login(email, PASSWORD, ip).andExpect(status().isOk());
	}

	@Test
	void countersArePerEmail() throws Exception {
		for (int i = 0; i < 5; i++) {
			login(email, "wrong", ip);
		}

		login(email, PASSWORD, ip).andExpect(status().isTooManyRequests());
		login(otherEmail, PASSWORD, ip).andExpect(status().isOk());
	}

	@Test
	void emailIsCountedCaseInsensitively() throws Exception {
		for (int i = 0; i < 5; i++) {
			login(i % 2 == 0 ? email : email.toUpperCase(), "wrong", ip);
		}

		login(email, PASSWORD, ip).andExpect(status().isTooManyRequests());
	}

	@Test
	void successfulLoginResetsTheEmailCounter() throws Exception {
		for (int i = 0; i < 4; i++) {
			login(email, "wrong", ip).andExpect(status().isUnauthorized());
		}
		login(email, PASSWORD, ip).andExpect(status().isOk());

		// Four more failures would have locked the account without the reset
		for (int i = 0; i < 4; i++) {
			login(email, "wrong", ip).andExpect(status().isUnauthorized());
		}
		login(email, PASSWORD, ip).andExpect(status().isOk());
	}

	@Test
	void oneAddressGuessingManyAccountsIsLockedOutAfterTwentyFailures() throws Exception {
		for (int i = 0; i < 20; i++) {
			login("spray-" + i + "-" + email, "wrong", ip).andExpect(status().isUnauthorized());
		}

		// That address is now refused for any account, valid password or not
		login(otherEmail, PASSWORD, ip).andExpect(status().isTooManyRequests());
		// The same account from another address is unaffected
		login(otherEmail, PASSWORD, "198.51.100.9").andExpect(status().isOk());
	}

}
