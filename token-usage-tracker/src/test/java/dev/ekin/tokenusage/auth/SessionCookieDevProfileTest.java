package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 'dev' profile serves the dashboard over plain http (local development
 * and the Docker demo), so its session cookie is not marked Secure. Runs in
 * its own context and database, which the dev seeder fills.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class SessionCookieDevProfileTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void devProfileSessionCookieIsNotSecureButStillHttpOnlyAndStrict() throws Exception {
		String setCookie = mockMvc.perform(post("/auth/token").contentType("application/json")
						.content("{\"email\": \"member1@acme.dev\", \"password\": \"%s\"}"
								.formatted(DataSeeder.SEED_PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

		assertThat(setCookie).contains("HttpOnly", "SameSite=Strict").doesNotContain("Secure");
	}

}
