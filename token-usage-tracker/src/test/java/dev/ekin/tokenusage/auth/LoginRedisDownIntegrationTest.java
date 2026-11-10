package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis is pointed at a port nothing listens on: the login rate limit must
 * fail open, so logging in still works and wrong passwords are still 401.
 */
@SpringBootTest(properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@Import(LoginRedisDownIntegrationTest.PostgresOnly.class)
class LoginRedisDownIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresOnly {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
		}

	}

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	MemberRepository memberRepository;

	@Test
	void loginWorksAndIsNotLimitedWithoutRedis() throws Exception {
		Organization org = organizationRepository.save(new Organization("No Redis", "no-redis-login"));
		memberRepository.save(new Member(org.getId(), null, "noredis@test.dev", "No Redis",
				passwordEncoder.encode("the-password"), "MEMBER"));

		for (int i = 0; i < 7; i++) {
			mockMvc.perform(post("/auth/token").contentType("application/json")
							.content("{\"email\": \"noredis@test.dev\", \"password\": \"wrong\"}"))
					.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/auth/token").contentType("application/json")
						.content("{\"email\": \"noredis@test.dev\", \"password\": \"the-password\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

}
