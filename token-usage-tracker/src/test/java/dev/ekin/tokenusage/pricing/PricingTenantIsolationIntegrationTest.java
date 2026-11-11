package dev.ekin.tokenusage.pricing;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.analytics.RollupRepository;
import dev.ekin.tokenusage.budget.BudgetAlertRepository;
import dev.ekin.tokenusage.budget.BudgetRepository;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.TeamRepository;
import dev.ekin.tokenusage.usage.UsageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A price posted by one org's admin must change that org's costs and no one
 * else's. Two orgs, each with an admin and a project, share one global price.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PricingTenantIsolationIntegrationTest {

	private static final String PASSWORD = "integration-test-pw";
	private static final Instant EVENT_TIME = Instant.parse("2026-06-15T10:30:00Z");
	/** Global price: $0.003 in, $0.015 out per 1k, so 1,000 in and 2,000 out cost $0.033. */
	private static final double GLOBAL_COST = 0.033;
	/** Org A's override: $0.006 in, $0.030 out per 1k, so the same event costs $0.066. */
	private static final double OVERRIDE_COST = 0.066;

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
	ModelPricingRepository modelPricingRepository;
	@Autowired
	UsageEventRepository usageEventRepository;
	@Autowired
	RollupRepository rollupRepository;
	@Autowired
	TeamRepository teamRepository;
	@Autowired
	BudgetRepository budgetRepository;
	@Autowired
	BudgetAlertRepository budgetAlertRepository;

	UUID orgA;
	Project projectA;
	Project projectB;

	@BeforeEach
	void setUp() {
		// Other test classes share this database, so clear everything that points at an org
		budgetAlertRepository.deleteAll();
		budgetRepository.deleteAll();
		rollupRepository.deleteAll();
		usageEventRepository.deleteAll();
		modelPricingRepository.deleteAll();
		memberRepository.deleteAll();
		projectRepository.deleteAll();
		teamRepository.deleteAll();
		organizationRepository.deleteAll();

		Organization a = organizationRepository.save(new Organization("Org A", "org-a"));
		Organization b = organizationRepository.save(new Organization("Org B", "org-b"));
		orgA = a.getId();
		memberRepository.save(new Member(a.getId(), null, "admin@a.dev", "Admin A",
				passwordEncoder.encode(PASSWORD), "ADMIN,MEMBER"));
		memberRepository.save(new Member(a.getId(), null, "member@a.dev", "Member A",
				passwordEncoder.encode(PASSWORD), "MEMBER"));
		memberRepository.save(new Member(b.getId(), null, "admin@b.dev", "Admin B",
				passwordEncoder.encode(PASSWORD), "ADMIN,MEMBER"));
		projectA = projectRepository.save(new Project(a.getId(), "project-a"));
		projectB = projectRepository.save(new Project(b.getId(), "project-b"));

		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
	}

	@Test
	void anOverrideChangesOnlyTheCallersOrg() throws Exception {
		String adminA = token("admin@a.dev");
		String adminB = token("admin@b.dev");

		postPrice(adminA, "claude-sonnet-4-6", "0.006", "0.030")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.scope").value("ORG"));

		assertThat(ingestCost(adminA, projectA, "claude-sonnet-4-6")).isEqualTo(OVERRIDE_COST);
		assertThat(ingestCost(adminB, projectB, "claude-sonnet-4-6")).isEqualTo(GLOBAL_COST);

		// The global row is untouched and the new row belongs to org A
		List<ModelPricing> rows = modelPricingRepository.findAll();
		assertThat(rows).hasSize(2);
		assertThat(rows).filteredOn(ModelPricing::isGlobal).singleElement()
				.extracting(ModelPricing::getInputPricePer1k)
				.satisfies(p -> assertThat(p).isEqualByComparingTo("0.003"));
		assertThat(rows).filteredOn(p -> !p.isGlobal()).singleElement()
				.extracting(ModelPricing::getOrgId).isEqualTo(orgA);
	}

	@Test
	void anOrgCannotLowerAnotherOrgsCostEither() throws Exception {
		// The attack in reverse: zeroing a price to hide one's own spend must not leak
		String adminA = token("admin@a.dev");
		postPrice(adminA, "claude-sonnet-4-6", "0", "0").andExpect(status().isCreated());

		assertThat(ingestCost(adminA, projectA, "claude-sonnet-4-6")).isEqualTo(0.0);
		assertThat(ingestCost(token("admin@b.dev"), projectB, "claude-sonnet-4-6")).isEqualTo(GLOBAL_COST);
	}

	@Test
	void theListingShowsGlobalRowsAndOnlyTheCallersOverrides() throws Exception {
		String adminA = token("admin@a.dev");
		String adminB = token("admin@b.dev");
		postPrice(adminA, "claude-sonnet-4-6", "0.006", "0.030").andExpect(status().isCreated());
		postPrice(adminB, "claude-haiku-4-5", "0.001", "0.005").andExpect(status().isCreated());

		assertThat(listing(adminA)).containsExactlyInAnyOrder(
				"GLOBAL claude-sonnet-4-6", "ORG claude-sonnet-4-6");
		assertThat(listing(adminB)).containsExactlyInAnyOrder(
				"GLOBAL claude-sonnet-4-6", "ORG claude-haiku-4-5");
		// A member sees the same prices as their admin: the list is read-only
		assertThat(listing(token("member@a.dev"))).containsExactlyInAnyOrder(
				"GLOBAL claude-sonnet-4-6", "ORG claude-sonnet-4-6");
	}

	@Test
	void anOverrideOnTheAliasAppliesToDatedIdsAndBeatsAGlobalExactMatch() throws Exception {
		// A global row for the exact dated id, at a price nobody should get for org A
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6-20260101",
				new BigDecimal("0.100"), new BigDecimal("0.100"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
		String adminA = token("admin@a.dev");
		postPrice(adminA, "claude-sonnet-4-6", "0.006", "0.030").andExpect(status().isCreated());

		// Org A: its own alias override wins over the global exact row
		assertThat(ingestCost(adminA, projectA, "claude-sonnet-4-6-20260101")).isEqualTo(OVERRIDE_COST);
		// Org B has no override, so the global exact row applies: (1000*0.1 + 2000*0.1) / 1000
		assertThat(ingestCost(token("admin@b.dev"), projectB, "claude-sonnet-4-6-20260101")).isEqualTo(0.3);
	}

	@Test
	void membersCannotPostPrices() throws Exception {
		postPrice(token("member@a.dev"), "claude-sonnet-4-6", "0", "0").andExpect(status().isForbidden());
		assertThat(modelPricingRepository.findAll()).hasSize(1);
	}

	// ---- helpers

	private String token(String email) throws Exception {
		String body = mockMvc.perform(post("/auth/token")
						.contentType("application/json")
						.content("""
								{"email": "%s", "password": "%s"}
								""".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, JsonNode.class).get("token").asString();
	}

	private org.springframework.test.web.servlet.ResultActions postPrice(String token, String model,
			String in, String out) throws Exception {
		return mockMvc.perform(post("/model-pricing")
				.header("Authorization", "Bearer " + token)
				.contentType("application/json")
				.content("""
						{"model": "%s", "inputPricePer1k": %s, "outputPricePer1k": %s,
						 "validFrom": "2026-01-01T00:00:00Z"}
						""".formatted(model, in, out)));
	}

	private double ingestCost(String token, Project project, String model) throws Exception {
		String body = mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content("""
								{"projectId": "%s", "model": "%s", "inputTokens": 1000,
								 "outputTokens": 2000, "eventTime": "%s"}
								""".formatted(project.getId(), model, EVENT_TIME)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, JsonNode.class).get("costUsd").asDouble();
	}

	private List<String> listing(String token) throws Exception {
		String body = mockMvc.perform(get("/model-pricing").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> out = new ArrayList<>();
		for (JsonNode row : objectMapper.readValue(body, JsonNode.class)) {
			out.add(row.get("scope").asString() + " " + row.get("model").asString());
		}
		return out;
	}

}
