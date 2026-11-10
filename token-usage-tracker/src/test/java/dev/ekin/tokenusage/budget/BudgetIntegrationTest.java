package dev.ekin.tokenusage.budget;

import com.sun.net.httpserver.HttpServer;
import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.analytics.RollupRepository;
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
import dev.ekin.tokenusage.usage.UsageEventRepository;
import dev.ekin.tokenusage.usage.UsageService;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Budgets end to end against real Postgres: admin CRUD with tenant
 * isolation, threshold evaluation from the monthly rollup, exactly-one alert
 * per threshold and month, and webhook delivery to a local stub server.
 *
 * <p>Pricing is $1 per 1k input tokens, so 1000 input tokens cost one dollar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BudgetIntegrationTest {

	private static final String PASSWORD = "integration-test-pw";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	UsageService usageService;
	@Autowired
	BudgetEvaluationJob budgetEvaluationJob;
	@Autowired
	BudgetAlertSender budgetAlertSender;
	@Autowired
	BudgetRepository budgetRepository;
	@Autowired
	BudgetAlertRepository budgetAlertRepository;
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
	RollupRepository rollupRepository;

	Organization org;
	Team team;
	Member admin;
	Member teamless;
	Project project;
	Project otherProject;

	HttpServer webhookServer;
	final List<String> webhookBodies = Collections.synchronizedList(new ArrayList<>());
	volatile int webhookStatus = 200;
	volatile long webhookDelayMillis;

	@BeforeEach
	void setUp() throws IOException {
		cleanDatabase();

		org = organizationRepository.save(new Organization("Budget Org", "budget-org"));
		team = teamRepository.save(new Team(org.getId(), "Platform"));
		admin = memberRepository.save(new Member(org.getId(), team.getId(),
				"admin@budget.dev", "Admin", passwordEncoder.encode(PASSWORD), "ADMIN,MEMBER"));
		teamless = memberRepository.save(new Member(org.getId(), null,
				"solo@budget.dev", "Solo", passwordEncoder.encode(PASSWORD), "MEMBER"));
		project = projectRepository.save(new Project(org.getId(), "checkout"));
		otherProject = projectRepository.save(new Project(org.getId(), "search"));
		modelPricingRepository.save(new ModelPricing("dollar-per-1k",
				new BigDecimal("1"), new BigDecimal("1"), Instant.parse("2020-01-01T00:00:00Z"), null));

		webhookServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		webhookServer.createContext("/hook", exchange -> {
			webhookBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(webhookDelayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(webhookStatus, -1);
			exchange.close();
		});
		webhookServer.start();
	}

	@AfterEach
	void tearDown() {
		webhookServer.stop(0);
		// Other test classes delete organizations; no budget may be left pointing at one
		cleanDatabase();
	}

	private void cleanDatabase() {
		budgetAlertRepository.deleteAll();
		budgetRepository.deleteAll();
		rollupRepository.deleteAll();
		usageEventRepository.deleteAll();
		modelPricingRepository.deleteAll();
		memberRepository.deleteAll();
		projectRepository.deleteAll();
		teamRepository.deleteAll();
		organizationRepository.deleteAll();
	}

	private String webhookUrl() {
		return "http://127.0.0.1:" + webhookServer.getAddress().getPort() + "/hook";
	}

	private String token(String email) throws Exception {
		String body = mockMvc.perform(post("/auth/token").contentType("application/json")
						.content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return "Bearer " + objectMapper.readValue(body, JsonNode.class).get("token").asString();
	}

	private String budgetJson(String scopeType, UUID scopeId, String limit) {
		return """
				{"scopeType": "%s", "scopeId": "%s", "monthlyLimitUsd": %s, "webhookUrl": "%s"}
				""".formatted(scopeType, scopeId, limit, webhookUrl());
	}

	private Budget projectBudget(String limit) {
		return budgetRepository.save(new Budget(org.getId(), BudgetScope.PROJECT, project.getId(),
				new BigDecimal(limit), webhookUrl(), true));
	}

	/** Books {@code dollars} of spend this month. */
	private void spend(Member member, Project onProject, int dollars) {
		spendAt(member, onProject, dollars, Instant.now().minusSeconds(1));
	}

	private void spendAt(Member member, Project onProject, int dollars, Instant eventTime) {
		usageService.ingest(member.getId(), new IngestCommand(onProject.getId(), "dollar-per-1k",
				dollars * 1000, 0, null, null, eventTime));
	}

	private List<BudgetAlert> alerts(Budget budget) {
		return budgetAlertRepository.findByBudgetIdOrderByPeriodStartDescThresholdPctDesc(budget.getId());
	}

	// ---- CRUD, roles, tenant isolation ----

	@Test
	void adminCreatesListsUpdatesAndDeletesABudget() throws Exception {
		String auth = token("admin@budget.dev");
		spend(admin, project, 25);

		String created = mockMvc.perform(post("/budgets").header("Authorization", auth)
						.contentType("application/json").content(budgetJson("PROJECT", project.getId(), "100")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.scopeType").value("PROJECT"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.monthlyLimitUsd").value(100))
				.andExpect(jsonPath("$.currentSpendUsd").value(25))
				.andReturn().getResponse().getContentAsString();
		String id = objectMapper.readValue(created, JsonNode.class).get("id").asString();

		mockMvc.perform(get("/budgets").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(id));

		mockMvc.perform(put("/budgets/" + id).header("Authorization", auth).contentType("application/json")
						.content("""
								{"monthlyLimitUsd": 250.50, "webhookUrl": "%s", "enabled": false}
								""".formatted(webhookUrl())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.monthlyLimitUsd").value(250.5))
				.andExpect(jsonPath("$.enabled").value(false));

		mockMvc.perform(get("/budgets/" + id + "/alerts").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		mockMvc.perform(delete("/budgets/" + id).header("Authorization", auth))
				.andExpect(status().isNoContent());
		assertThat(budgetRepository.count()).isZero();
	}

	@Test
	void nonAdminIsForbiddenOnEveryEndpoint() throws Exception {
		String auth = token("solo@budget.dev");
		Budget budget = projectBudget("100");

		mockMvc.perform(post("/budgets").header("Authorization", auth)
						.contentType("application/json").content(budgetJson("TEAM", team.getId(), "100")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/budgets").header("Authorization", auth)).andExpect(status().isForbidden());
		mockMvc.perform(get("/budgets/" + budget.getId() + "/alerts").header("Authorization", auth))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/budgets/" + budget.getId()).header("Authorization", auth))
				.andExpect(status().isForbidden());
		assertThat(budgetRepository.count()).isEqualTo(1);
	}

	@Test
	void anotherOrgsProjectTeamAndBudgetAreReportedAsNotFound() throws Exception {
		Organization foreignOrg = organizationRepository.save(new Organization("Foreign", "foreign"));
		Project foreignProject = projectRepository.save(new Project(foreignOrg.getId(), "theirs"));
		Team foreignTeam = teamRepository.save(new Team(foreignOrg.getId(), "Theirs"));
		Budget foreignBudget = budgetRepository.save(new Budget(foreignOrg.getId(), BudgetScope.PROJECT,
				foreignProject.getId(), new BigDecimal("50"), webhookUrl(), true));
		String auth = token("admin@budget.dev");

		mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
						.content(budgetJson("PROJECT", foreignProject.getId(), "100")))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
						.content(budgetJson("TEAM", foreignTeam.getId(), "100")))
				.andExpect(status().isNotFound());
		// A project id used as a team id is not a team
		mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
						.content(budgetJson("TEAM", project.getId(), "100")))
				.andExpect(status().isNotFound());

		mockMvc.perform(get("/budgets").header("Authorization", auth))
				.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/budgets/" + foreignBudget.getId() + "/alerts").header("Authorization", auth))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/budgets/" + foreignBudget.getId()).header("Authorization", auth)
						.contentType("application/json").content("""
								{"monthlyLimitUsd": 1, "webhookUrl": "%s", "enabled": true}
								""".formatted(webhookUrl())))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/budgets/" + foreignBudget.getId()).header("Authorization", auth))
				.andExpect(status().isNotFound());
		assertThat(budgetRepository.findById(foreignBudget.getId())).isPresent();
	}

	@Test
	void secondBudgetForTheSameScopeIsAConflictAndBadInputIsRejected() throws Exception {
		String auth = token("admin@budget.dev");
		mockMvc.perform(post("/budgets").header("Authorization", auth)
						.contentType("application/json").content(budgetJson("PROJECT", project.getId(), "100")))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/budgets").header("Authorization", auth)
						.contentType("application/json").content(budgetJson("PROJECT", project.getId(), "200")))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/budgets").header("Authorization", auth)
						.contentType("application/json").content(budgetJson("PROJECT", otherProject.getId(), "0")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.monthlyLimitUsd").isNotEmpty());
		mockMvc.perform(post("/budgets").header("Authorization", auth).contentType("application/json")
						.content(budgetJson("PROJECT", otherProject.getId(), "100")
								.replace(webhookUrl(), "ftp://example.com/hook")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("webhookUrl must use https"));
		assertThat(budgetRepository.count()).isEqualTo(1);
	}

	// ---- Evaluation ----

	@Test
	void alertsFireOncePerThresholdAsSpendCrossesEightyAndHundredPercent() {
		Budget budget = projectBudget("100");

		spend(admin, project, 79);
		assertThat(budgetEvaluationJob.evaluate()).isZero();
		assertThat(alerts(budget)).isEmpty();

		spend(admin, project, 1); // exactly 80%
		assertThat(budgetEvaluationJob.evaluate()).isEqualTo(1);
		assertThat(budgetEvaluationJob.evaluate()).isZero();
		assertThat(alerts(budget)).singleElement().satisfies(alert -> {
			assertThat(alert.getThresholdPct()).isEqualTo(80);
			assertThat(alert.getSpendUsd()).isEqualByComparingTo("80");
			assertThat(alert.getLimitUsd()).isEqualByComparingTo("100");
			assertThat(alert.getPeriodStart()).isEqualTo(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));
			assertThat(alert.getStatus()).isEqualTo(BudgetAlert.Status.PENDING);
		});

		spend(admin, project, 25); // 105%
		assertThat(budgetEvaluationJob.evaluate()).isEqualTo(1);
		assertThat(budgetEvaluationJob.evaluate()).isZero();
		assertThat(alerts(budget)).extracting(BudgetAlert::getThresholdPct).containsExactly(100, 80);
	}

	@Test
	void jumpingStraightPastHundredPercentRaisesBothAlerts() {
		Budget budget = projectBudget("100");
		spend(admin, project, 150);

		assertThat(budgetEvaluationJob.evaluate()).isEqualTo(2);
		assertThat(alerts(budget)).extracting(BudgetAlert::getThresholdPct).containsExactly(100, 80);
	}

	@Test
	void eachMonthAlertsOnItsOwn() {
		Budget budget = projectBudget("100");
		LocalDate thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
		LocalDate lastMonth = thisMonth.minusMonths(1);
		spendAt(admin, project, 90, lastMonth.plusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant());
		spend(admin, project, 90);

		assertThat(budgetEvaluationJob.evaluate(lastMonth)).isEqualTo(1);
		assertThat(budgetEvaluationJob.evaluate()).isEqualTo(1);

		assertThat(alerts(budget)).extracting(BudgetAlert::getPeriodStart)
				.containsExactly(thisMonth, lastMonth);
	}

	@Test
	void disabledBudgetAndSpendOnOtherProjectsRaiseNothing() {
		budgetRepository.save(new Budget(org.getId(), BudgetScope.PROJECT, project.getId(),
				new BigDecimal("100"), webhookUrl(), false));
		Budget other = budgetRepository.save(new Budget(org.getId(), BudgetScope.PROJECT,
				otherProject.getId(), new BigDecimal("100"), webhookUrl(), true));
		spend(admin, project, 500);

		assertThat(budgetEvaluationJob.evaluate()).isZero();
		assertThat(alerts(other)).isEmpty();
	}

	@Test
	void teamBudgetCountsTheTeamAcrossProjectsButNotTeamlessMembers() throws Exception {
		Budget budget = budgetRepository.save(new Budget(org.getId(), BudgetScope.TEAM, team.getId(),
				new BigDecimal("100"), webhookUrl(), true));
		spend(admin, project, 50);
		spend(admin, otherProject, 20);
		spend(teamless, project, 500);

		assertThat(budgetEvaluationJob.evaluate()).isZero();
		mockMvc.perform(get("/budgets").header("Authorization", token("admin@budget.dev")))
				.andExpect(jsonPath("$[0].currentSpendUsd").value(70));

		spend(admin, otherProject, 10);
		assertThat(budgetEvaluationJob.evaluate()).isEqualTo(1);
		assertThat(alerts(budget)).singleElement()
				.satisfies(alert -> assertThat(alert.getSpendUsd()).isEqualByComparingTo("80"));
	}

	@Test
	void parallelEvaluationsRaiseEachAlertExactlyOnce() throws Exception {
		Budget budget = projectBudget("100");
		spend(admin, project, 150);
		int runs = 20;
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		try (ExecutorService pool = Executors.newFixedThreadPool(runs)) {
			for (int i = 0; i < runs; i++) {
				pool.submit(() -> {
					try {
						start.await();
						budgetEvaluationJob.evaluate();
					} catch (Throwable t) {
						failures.add(t);
					}
				});
			}
			start.countDown();
		}

		assertThat(failures).as("%s", failures).isEmpty();
		assertThat(alerts(budget)).extracting(BudgetAlert::getThresholdPct).containsExactly(100, 80);
	}

	// ---- Delivery ----

	@Test
	void raisedAlertIsPostedToTheWebhookOnceAndMarkedSent() throws Exception {
		Budget budget = projectBudget("100");
		spend(admin, project, 85);

		budgetEvaluationJob.run();
		budgetEvaluationJob.run();

		assertThat(webhookBodies).hasSize(1);
		JsonNode payload = objectMapper.readValue(webhookBodies.getFirst(), JsonNode.class);
		BudgetAlert alert = alerts(budget).getFirst();
		assertThat(payload.get("alertId").asString()).isEqualTo(alert.getId().toString());
		assertThat(payload.get("budgetId").asString()).isEqualTo(budget.getId().toString());
		assertThat(payload.get("scopeType").asString()).isEqualTo("PROJECT");
		assertThat(payload.get("scopeId").asString()).isEqualTo(project.getId().toString());
		assertThat(payload.get("thresholdPct").asInt()).isEqualTo(80);
		assertThat(payload.get("spendUsd").decimalValue()).isEqualByComparingTo("85");
		assertThat(payload.get("limitUsd").decimalValue()).isEqualByComparingTo("100");
		assertThat(alert.getStatus()).isEqualTo(BudgetAlert.Status.SENT);
		assertThat(alert.getAttempts()).isEqualTo(1);
		assertThat(alert.getSentAt()).isNotNull();

		mockMvc.perform(get("/budgets/" + budget.getId() + "/alerts")
						.header("Authorization", token("admin@budget.dev")))
				.andExpect(jsonPath("$[0].status").value("SENT"))
				.andExpect(jsonPath("$[0].thresholdPct").value(80));
	}

	@Test
	void failingWebhookIsRetriedThenGivenUpAfterFiveAttempts() {
		Budget budget = projectBudget("100");
		spend(admin, project, 85);
		budgetEvaluationJob.evaluate();
		webhookStatus = 500;

		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(budgetAlertSender.sendPending()).isZero();
			BudgetAlert alert = alerts(budget).getFirst();
			assertThat(alert.getAttempts()).isEqualTo(attempt);
			assertThat(alert.getStatus()).isEqualTo(BudgetAlert.Status.PENDING);
		}
		budgetAlertSender.sendPending();
		assertThat(alerts(budget).getFirst().getStatus()).isEqualTo(BudgetAlert.Status.FAILED);

		// A failed alert is left alone, and the recovered webhook is not called for it
		webhookStatus = 200;
		budgetAlertSender.sendPending();
		assertThat(webhookBodies).hasSize(5);
		assertThat(alerts(budget).getFirst().getStatus()).isEqualTo(BudgetAlert.Status.FAILED);
	}

	@Test
	void webhookRecoveringBeforeTheLastAttemptGetsTheAlert() {
		Budget budget = projectBudget("100");
		spend(admin, project, 85);
		budgetEvaluationJob.evaluate();
		webhookStatus = 503;
		budgetAlertSender.sendPending();

		webhookStatus = 200;
		assertThat(budgetAlertSender.sendPending()).isEqualTo(1);

		BudgetAlert alert = alerts(budget).getFirst();
		assertThat(alert.getStatus()).isEqualTo(BudgetAlert.Status.SENT);
		assertThat(alert.getAttempts()).isEqualTo(2);
	}

	@Test
	void hangingWebhookTimesOutAndIngestIsUnaffected() {
		Budget budget = projectBudget("100");
		spend(admin, project, 85);
		budgetEvaluationJob.evaluate();
		webhookDelayMillis = 4000; // the suite's read timeout is 1 s

		long started = System.nanoTime();
		assertThat(budgetAlertSender.sendPending()).isZero();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(3000));

		assertThat(alerts(budget).getFirst().getStatus()).isEqualTo(BudgetAlert.Status.PENDING);
		spend(admin, project, 1);
		assertThat(usageEventRepository.count()).isEqualTo(2);
	}

	@Test
	void deletingABudgetRemovesItsAlerts() throws Exception {
		Budget budget = projectBudget("100");
		spend(admin, project, 150);
		budgetEvaluationJob.evaluate();
		assertThat(budgetAlertRepository.count()).isEqualTo(2);

		mockMvc.perform(delete("/budgets/" + budget.getId()).header("Authorization", token("admin@budget.dev")))
				.andExpect(status().isNoContent());

		assertThat(budgetAlertRepository.count()).isZero();
	}

}
