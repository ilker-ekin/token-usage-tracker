package dev.ekin.tokenusage.budget;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The production setting: webhook URLs must be https and must not be internal. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.budgets.allow-insecure-webhook-urls=false")
class BudgetStrictWebhookUrlIntegrationTest {

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
	ProjectRepository projectRepository;
	@Autowired
	BudgetRepository budgetRepository;

	@AfterEach
	void cleanUp() {
		budgetRepository.deleteAll();
		memberRepository.deleteAll();
		projectRepository.deleteAll();
		organizationRepository.deleteAll();
	}

	@Test
	void plainHttpAndPrivateAddressesAreRejectedAndAPublicHttpsUrlIsAccepted() throws Exception {
		Organization org = organizationRepository.save(new Organization("Strict Org", "strict-org"));
		memberRepository.save(new Member(org.getId(), null, "admin@strict.dev", "Admin",
				passwordEncoder.encode("pw-for-strict-test"), "ADMIN"));
		Project project = projectRepository.save(new Project(org.getId(), "p"));
		String login = mockMvc.perform(post("/auth/token").contentType("application/json")
						.content("{\"email\": \"admin@strict.dev\", \"password\": \"pw-for-strict-test\"}"))
				.andReturn().getResponse().getContentAsString();
		String auth = "Bearer " + objectMapper.readValue(login, JsonNode.class).get("token").asString();
		String json = """
				{"scopeType": "PROJECT", "scopeId": "%s", "monthlyLimitUsd": 100, "webhookUrl": "%%s"}
				""".formatted(project.getId());

		mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
						.content(json.formatted("http://8.8.8.8/hook")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("webhookUrl must use https"));
		for (String internal : new String[] {"https://127.0.0.1/hook", "https://10.1.2.3/hook",
				"https://169.254.169.254/latest/meta-data"}) {
			mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
							.content(json.formatted(internal)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail")
							.value("webhookUrl must not point to a private or local address"));
		}
		assertThat(budgetRepository.count()).isZero();

		mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
						.content(json.formatted("https://8.8.8.8/hook")))
				.andExpect(status().isCreated());
	}

}
