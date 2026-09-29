package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.analytics.DailyRollup;
import dev.ekin.tokenusage.analytics.DailyRollupRepository;
import dev.ekin.tokenusage.analytics.MonthlyRollup;
import dev.ekin.tokenusage.analytics.MonthlyRollupRepository;
import dev.ekin.tokenusage.analytics.RollupRecomputeJob;
import dev.ekin.tokenusage.analytics.WeeklyRollup;
import dev.ekin.tokenusage.analytics.WeeklyRollupRepository;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.ModelPricingRepository;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.Team;
import dev.ekin.tokenusage.tenant.TeamRepository;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full ingest flow against real Postgres: authenticate, POST /usage, then
 * assert the event and all three rollup rows landed with the right numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UsageIngestionIntegrationTest {

	private static final String PASSWORD = "integration-test-pw";
	private static final Instant EVENT_TIME = Instant.parse("2026-06-15T10:30:00Z");

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	TeamRepository teamRepository;
	@Autowired
	MemberRepository memberRepository;
	@Autowired
	ProjectRepository projectRepository;
	@Autowired
	ModelPricingRepository modelPricingRepository;
	@Autowired
	UsageEventRepository usageEventRepository;
	@Autowired
	DailyRollupRepository dailyRollupRepository;
	@Autowired
	WeeklyRollupRepository weeklyRollupRepository;
	@Autowired
	MonthlyRollupRepository monthlyRollupRepository;
	@Autowired
	RollupRecomputeJob rollupRecomputeJob;

	Member member;
	Project project;

	@BeforeEach
	void setUp() {
		dailyRollupRepository.deleteAll();
		weeklyRollupRepository.deleteAll();
		monthlyRollupRepository.deleteAll();
		usageEventRepository.deleteAll();
		modelPricingRepository.deleteAll();
		memberRepository.deleteAll();
		projectRepository.deleteAll();
		teamRepository.deleteAll();
		organizationRepository.deleteAll();

		Organization org = organizationRepository.save(new Organization("Test Org", "test-org"));
		Team team = teamRepository.save(new Team(org.getId(), "Test Team"));
		member = memberRepository.save(new Member(org.getId(), team.getId(),
				"tester@test.dev", "Tester", passwordEncoder.encode(PASSWORD), "ADMIN,MEMBER"));
		project = projectRepository.save(new Project(org.getId(), "test-project"));
		// LiteLLM prices for claude-sonnet-4-6: $3/M input, $15/M output
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
	}

	String obtainToken() throws Exception {
		String body = mockMvc.perform(post("/auth/token")
						.contentType("application/json")
						.content("""
								{"email": "tester@test.dev", "password": "%s"}
								""".formatted(PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, JsonNode.class).get("token").asString();
	}

	String ingestJson() {
		return """
				{
				  "projectId": "%s",
				  "model": "claude-sonnet-4-6",
				  "inputTokens": 1000,
				  "outputTokens": 2000,
				  "linesAdded": 42,
				  "linesRemoved": 7,
				  "eventTime": "%s"
				}
				""".formatted(project.getId(), EVENT_TIME);
	}

	@Test
	void wrongPasswordIsRejected() throws Exception {
		mockMvc.perform(post("/auth/token")
						.contentType("application/json")
						.content("""
								{"email": "tester@test.dev", "password": "wrong"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void meReturnsClaimsFromToken() throws Exception {
		String token = obtainToken();
		mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memberId").value(member.getId().toString()))
				.andExpect(jsonPath("$.orgId").value(member.getOrgId().toString()))
				.andExpect(jsonPath("$.email").value("tester@test.dev"))
				.andExpect(jsonPath("$.roles[0]").value("ADMIN"));
	}

	@Test
	void ingestPersistsEventAndAllThreeRollups() throws Exception {
		String token = obtainToken();

		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content(ingestJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				// (1000 * 0.003 + 2000 * 0.015) / 1000 = 0.033
				.andExpect(jsonPath("$.costUsd").value(0.033));

		UsageEvent event = usageEventRepository.findAll().getFirst();
		assertThat(event.getMemberId()).isEqualTo(member.getId());
		assertThat(event.getOrgId()).isEqualTo(member.getOrgId());
		assertThat(event.getCostUsd()).isEqualByComparingTo("0.033");

		LocalDate day = LocalDate.ofInstant(EVENT_TIME, ZoneOffset.UTC);

		DailyRollup daily = dailyRollupRepository.findAll().getFirst();
		assertThat(daily.getRollupDate()).isEqualTo(day);
		assertThat(daily.getTotalInputTokens()).isEqualTo(1000);
		assertThat(daily.getTotalOutputTokens()).isEqualTo(2000);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo("0.033");
		assertThat(daily.getTotalLinesAdded()).isEqualTo(42);
		assertThat(daily.getTotalLinesRemoved()).isEqualTo(7);

		WeeklyRollup weekly = weeklyRollupRepository.findAll().getFirst();
		assertThat(weekly.getWeekStart()).isEqualTo(day.with(DayOfWeek.MONDAY));
		assertThat(weekly.getTotalCostUsd()).isEqualByComparingTo("0.033");

		MonthlyRollup monthly = monthlyRollupRepository.findAll().getFirst();
		assertThat(monthly.getMonthStart()).isEqualTo(day.withDayOfMonth(1));
		assertThat(monthly.getTotalCostUsd()).isEqualByComparingTo("0.033");
	}

	@Test
	void secondEventSameDayAccumulatesIntoExistingRollups() throws Exception {
		String token = obtainToken();
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/usage")
							.header("Authorization", "Bearer " + token)
							.contentType("application/json")
							.content(ingestJson()))
					.andExpect(status().isCreated());
		}

		assertThat(usageEventRepository.count()).isEqualTo(2);
		assertThat(dailyRollupRepository.count()).isEqualTo(1);

		DailyRollup daily = dailyRollupRepository.findAll().getFirst();
		assertThat(daily.getTotalInputTokens()).isEqualTo(2000);
		assertThat(daily.getTotalOutputTokens()).isEqualTo(4000);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo("0.066");
	}

	@Test
	void ingestIntoAnotherOrgsProjectIsRejectedAsNotFound() throws Exception {
		Organization otherOrg = organizationRepository.save(new Organization("Other Org", "other-org"));
		Project foreign = projectRepository.save(new Project(otherOrg.getId(), "foreign-project"));
		String token = obtainToken();

		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content(ingestJson().replace(project.getId().toString(), foreign.getId().toString())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Project not found: " + foreign.getId()));

		assertThat(usageEventRepository.count()).isZero();
		assertThat(dailyRollupRepository.count()).isZero();
	}

	@Test
	void ingestIntoUnknownProjectLooksTheSameAsForeignProject() throws Exception {
		UUID unknown = UUID.randomUUID();
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(ingestJson().replace(project.getId().toString(), unknown.toString())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Project not found: " + unknown));
	}

	@Test
	void ingestWithUnknownModelReturns422() throws Exception {
		String token = obtainToken();
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content(ingestJson().replace("claude-sonnet-4-6", "unknown-model")))
				.andExpect(status().isUnprocessableContent());
	}

	@Test
	void recomputeRebuildsRollupsFromEvents() throws Exception {
		String token = obtainToken();
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content(ingestJson()))
				.andExpect(status().isCreated());

		// Corrupt the daily rollup, then recompute the window covering the event
		dailyRollupRepository.deleteAll();
		LocalDate day = LocalDate.ofInstant(EVENT_TIME, ZoneOffset.UTC);
		rollupRecomputeJob.recompute(day, day);

		DailyRollup daily = dailyRollupRepository.findAll().getFirst();
		assertThat(daily.getTotalInputTokens()).isEqualTo(1000);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo("0.033");
	}

	@Test
	void pricingAdminEndpointRequiresAdminRole() throws Exception {
		memberRepository.save(new Member(member.getOrgId(), member.getTeamId(),
				"plain@test.dev", "Plain", passwordEncoder.encode(PASSWORD), "MEMBER"));
		String body = mockMvc.perform(post("/auth/token")
						.contentType("application/json")
						.content("""
								{"email": "plain@test.dev", "password": "%s"}
								""".formatted(PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String memberToken = objectMapper.readValue(body, JsonNode.class).get("token").asString();

		String pricingJson = """
				{
				  "model": "claude-haiku-4-5",
				  "inputPricePer1k": 0.001,
				  "outputPricePer1k": 0.005,
				  "validFrom": "2026-01-01T00:00:00Z"
				}
				""";

		mockMvc.perform(post("/model-pricing")
						.header("Authorization", "Bearer " + memberToken)
						.contentType("application/json")
						.content(pricingJson))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/model-pricing")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(pricingJson))
				.andExpect(status().isCreated());
	}

}
