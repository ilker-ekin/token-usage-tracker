package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.auth.JwtService;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.ModelPricingRepository;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis is pointed at a port nothing listens on: ingest and analytics must
 * still succeed, with the cache degrading to a pass-through.
 */
@SpringBootTest(properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@Import(AnalyticsRedisDownIntegrationTest.PostgresOnly.class)
class AnalyticsRedisDownIntegrationTest {

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
	JwtService jwtService;
	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	MemberRepository memberRepository;
	@Autowired
	ProjectRepository projectRepository;
	@Autowired
	ModelPricingRepository modelPricingRepository;

	@Test
	void ingestAndAnalyticsWorkWithoutRedis() throws Exception {
		Organization org = organizationRepository.save(new Organization("No Cache", "no-cache"));
		Member member = memberRepository.save(new Member(org.getId(), null,
				"nocache@test.dev", "No Cache", null, "MEMBER"));
		Project project = projectRepository.save(new Project(org.getId(), "offline"));
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
		String token = jwtService.issueToken(member);

		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content("""
								{"projectId": "%s", "model": "claude-sonnet-4-6",
								 "inputTokens": 1000, "outputTokens": 2000,
								 "eventTime": "2026-06-15T10:30:00Z"}
								""".formatted(project.getId())))
				.andExpect(status().isCreated());

		// Twice: the second read must not depend on anything the first cached
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(get("/analytics/projects?from=2026-06-01&to=2026-06-30")
							.header("Authorization", "Bearer " + token))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].name").value("offline"))
					.andExpect(jsonPath("$[0].costUsd").value(0.033));
		}
	}

}
