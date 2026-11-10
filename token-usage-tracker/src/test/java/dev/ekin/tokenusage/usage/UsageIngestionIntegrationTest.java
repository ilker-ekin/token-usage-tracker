package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.analytics.Granularity;
import dev.ekin.tokenusage.analytics.Rollup;
import dev.ekin.tokenusage.analytics.RollupRepository;
import dev.ekin.tokenusage.analytics.RollupRecomputeJob;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.ModelPricingRepository;
import dev.ekin.tokenusage.pricing.PricingCatalogUnavailableException;
import dev.ekin.tokenusage.pricing.StubLiteLlmPricingClient;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
	RollupRepository rollupRepository;
	@Autowired
	RollupRecomputeJob rollupRecomputeJob;
	@Autowired
	UsageService usageService;
	@Autowired
	StubLiteLlmPricingClient pricingCatalog;
	@Autowired
	StringRedisTemplate redisTemplate;

	Member member;
	Project project;

	@BeforeEach
	void setUp() {
		rollupRepository.deleteAll();
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

		Rollup daily = rollupRepository.findByGranularity(Granularity.DAY).getFirst();
		assertThat(daily.getPeriodStart()).isEqualTo(day);
		assertThat(daily.getTotalInputTokens()).isEqualTo(1000);
		assertThat(daily.getTotalOutputTokens()).isEqualTo(2000);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo("0.033");
		assertThat(daily.getTotalLinesAdded()).isEqualTo(42);
		assertThat(daily.getTotalLinesRemoved()).isEqualTo(7);

		Rollup weekly = rollupRepository.findByGranularity(Granularity.WEEK).getFirst();
		assertThat(weekly.getPeriodStart()).isEqualTo(day.with(DayOfWeek.MONDAY));
		assertThat(weekly.getTotalCostUsd()).isEqualByComparingTo("0.033");

		Rollup monthly = rollupRepository.findByGranularity(Granularity.MONTH).getFirst();
		assertThat(monthly.getPeriodStart()).isEqualTo(day.withDayOfMonth(1));
		assertThat(monthly.getTotalCostUsd()).isEqualByComparingTo("0.033");
	}

	String cacheIngestJson() {
		return ingestJson().replace("\"outputTokens\": 2000,",
				"\"outputTokens\": 2000, \"cacheReadTokens\": 10000, \"cacheCreationTokens\": 4000,");
	}

	@Test
	void ingestWithCacheTokensPricesThemSeparatelyAndRollsThemUp() throws Exception {
		modelPricingRepository.deleteAll();
		// Cache read 0.1x and cache write 1.25x of the $3/M input price
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				new BigDecimal("0.0003"), new BigDecimal("0.00375"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
		String token = obtainToken();

		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content(cacheIngestJson()))
				.andExpect(status().isCreated())
				// 0.033 + (10000 * 0.0003 + 4000 * 0.00375) / 1000 = 0.033 + 0.003 + 0.015
				.andExpect(jsonPath("$.costUsd").value(0.051));

		UsageEvent event = usageEventRepository.findAll().getFirst();
		assertThat(event.getInputTokens()).isEqualTo(1000);
		assertThat(event.getCacheReadTokens()).isEqualTo(10_000);
		assertThat(event.getCacheCreationTokens()).isEqualTo(4000);

		Rollup daily = rollupRepository.findByGranularity(Granularity.DAY).getFirst();
		assertThat(daily.getTotalInputTokens()).isEqualTo(1000);
		assertThat(daily.getTotalCacheReadTokens()).isEqualTo(10_000);
		assertThat(daily.getTotalCacheCreationTokens()).isEqualTo(4000);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo("0.051");

		Rollup weekly = rollupRepository.findByGranularity(Granularity.WEEK).getFirst();
		assertThat(weekly.getTotalCacheReadTokens()).isEqualTo(10_000);
		assertThat(weekly.getTotalCacheCreationTokens()).isEqualTo(4000);

		Rollup monthly = rollupRepository.findByGranularity(Granularity.MONTH).getFirst();
		assertThat(monthly.getTotalCacheReadTokens()).isEqualTo(10_000);
		assertThat(monthly.getTotalCacheCreationTokens()).isEqualTo(4000);

		mockMvc.perform(get("/usage").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].cacheReadTokens").value(10_000))
				.andExpect(jsonPath("$.content[0].cacheCreationTokens").value(4000));

		// The nightly rebuild reproduces the cache totals from the events
		rollupRepository.deleteByGranularity(Granularity.DAY);
		LocalDate day = LocalDate.ofInstant(EVENT_TIME, ZoneOffset.UTC);
		rollupRecomputeJob.recompute(day, day);
		Rollup rebuilt = rollupRepository.findByGranularity(Granularity.DAY).getFirst();
		assertThat(rebuilt.getTotalCacheReadTokens()).isEqualTo(10_000);
		assertThat(rebuilt.getTotalCacheCreationTokens()).isEqualTo(4000);
		assertThat(rebuilt.getTotalCostUsd()).isEqualByComparingTo("0.051");
	}

	/** A pricing row from before cache prices existed has NULL in both columns. */
	@Test
	void cacheTokensAreChargedAtTheInputPriceWhenThePricingRowHasNoCachePrices() throws Exception {
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(cacheIngestJson()))
				.andExpect(status().isCreated())
				// 0.033 + (10000 + 4000) * 0.003 / 1000 = 0.033 + 0.042
				.andExpect(jsonPath("$.costUsd").value(0.075));
	}

	@Test
	void ingestWithoutCacheFieldsStoresZeroCacheTokens() throws Exception {
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(ingestJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.costUsd").value(0.033));

		UsageEvent event = usageEventRepository.findAll().getFirst();
		assertThat(event.getCacheReadTokens()).isZero();
		assertThat(event.getCacheCreationTokens()).isZero();
		assertThat(rollupRepository.findByGranularity(Granularity.DAY).getFirst().getTotalCacheReadTokens()).isZero();
	}

	@Test
	void negativeCacheTokensAreRejected() throws Exception {
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(cacheIngestJson().replace("10000", "-1")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void pricingEndpointAcceptsAndReturnsCachePrices() throws Exception {
		String token = obtainToken();
		mockMvc.perform(post("/model-pricing")
						.header("Authorization", "Bearer " + token)
						.contentType("application/json")
						.content("""
								{
								  "model": "gpt-4o-mini",
								  "inputPricePer1k": 0.00015,
								  "outputPricePer1k": 0.0006,
								  "cacheReadPricePer1k": 0.0000375,
								  "validFrom": "2026-01-01T00:00:00Z"
								}
								"""))
				.andExpect(status().isCreated())
				// Seven decimals survive the round trip through NUMERIC(18,10)
				.andExpect(jsonPath("$.cacheReadPricePer1k").value(0.0000375))
				.andExpect(jsonPath("$.cacheCreationPricePer1k").doesNotExist());
	}

	@Test
	void replayWithSameIdempotencyKeyReturnsTheFirstEventAndBooksNothing() throws Exception {
		String token = obtainToken();
		String first = mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", "retry-1")
						.contentType("application/json")
						.content(ingestJson()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String firstId = objectMapper.readValue(first, JsonNode.class).get("id").asString();
		String versionKey = "analytics:ver:" + member.getOrgId();
		String versionAfterFirst = redisTemplate.opsForValue().get(versionKey);
		assertThat(versionAfterFirst).isNotNull();

		// The replay even carries different numbers: the original event wins
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", "retry-1")
						.contentType("application/json")
						.content(ingestJson().replace("1000", "999999")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(firstId))
				.andExpect(jsonPath("$.costUsd").value(0.033));

		assertThat(usageEventRepository.count()).isEqualTo(1);
		assertThat(usageEventRepository.findAll().getFirst().getIdempotencyKey()).isEqualTo("retry-1");
		Rollup daily = rollupRepository.findByGranularity(Granularity.DAY).getFirst();
		assertThat(daily.getTotalInputTokens()).isEqualTo(1000);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo("0.033");
		assertThat(rollupRepository.findByGranularity(Granularity.WEEK).getFirst().getTotalCostUsd()).isEqualByComparingTo("0.033");
		assertThat(rollupRepository.findByGranularity(Granularity.MONTH).getFirst().getTotalCostUsd()).isEqualByComparingTo("0.033");
		// A replay writes nothing, so the org's analytics cache stays valid
		assertThat(redisTemplate.opsForValue().get(versionKey)).isEqualTo(versionAfterFirst);
	}

	@Test
	void replaySucceedsEvenIfThePricingRowIsGone() throws Exception {
		String token = obtainToken();
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", "retry-2")
						.contentType("application/json")
						.content(ingestJson()))
				.andExpect(status().isCreated());
		modelPricingRepository.deleteAll();

		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + token)
						.header("Idempotency-Key", "retry-2")
						.contentType("application/json")
						.content(ingestJson().replace("claude-sonnet-4-6", "unknown-model")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.costUsd").value(0.033));
	}

	@Test
	void differentIdempotencyKeysBookSeparateEvents() throws Exception {
		String token = obtainToken();
		for (String key : new String[] {"a", "b"}) {
			mockMvc.perform(post("/usage")
							.header("Authorization", "Bearer " + token)
							.header("Idempotency-Key", key)
							.contentType("application/json")
							.content(ingestJson()))
					.andExpect(status().isCreated());
		}
		assertThat(usageEventRepository.count()).isEqualTo(2);
		assertThat(rollupRepository.findByGranularity(Granularity.DAY).getFirst().getTotalInputTokens()).isEqualTo(2000);
	}

	@Test
	void sameIdempotencyKeyInTwoOrgsBooksTwoEvents() {
		Organization otherOrg = organizationRepository.save(new Organization("Other Org", "other-org"));
		Member otherMember = memberRepository.save(new Member(otherOrg.getId(), null,
				"other@other.dev", "Other", null, "MEMBER"));
		Project otherProject = projectRepository.save(new Project(otherOrg.getId(), "other-project"));

		UsageService.IngestResult mine = usageService.ingest(member.getId(),
				command(project.getId()), "shared-key");
		UsageService.IngestResult theirs = usageService.ingest(otherMember.getId(),
				command(otherProject.getId()), "shared-key");

		assertThat(mine.created()).isTrue();
		assertThat(theirs.created()).isTrue();
		assertThat(theirs.event().getId()).isNotEqualTo(mine.event().getId());
		assertThat(usageEventRepository.count()).isEqualTo(2);
	}

	@Test
	void keyedIngestStoresNullLinesAndTeam() {
		Member teamless = memberRepository.save(new Member(member.getOrgId(), null,
				"solo@test.dev", "Solo", null, "MEMBER"));
		UsageEvent event = usageService.ingest(teamless.getId(), new UsageService.IngestCommand(
				project.getId(), "claude-sonnet-4-6", 1000, 2000, null, null, EVENT_TIME), "k").event();

		assertThat(event.getTeamId()).isNull();
		assertThat(event.getLinesAdded()).isNull();
		assertThat(event.getLinesRemoved()).isNull();
		assertThat(event.getCostUsd()).isEqualByComparingTo("0.033");
		assertThat(event.getCreatedAt()).isNotNull();
	}

	@Test
	void blankOrOversizedIdempotencyKeyIsRejected() throws Exception {
		String token = obtainToken();
		for (String key : new String[] {" ", "k".repeat(256)}) {
			mockMvc.perform(post("/usage")
							.header("Authorization", "Bearer " + token)
							.header("Idempotency-Key", key)
							.contentType("application/json")
							.content(ingestJson()))
					.andExpect(status().isBadRequest());
		}
		assertThat(usageEventRepository.count()).isZero();
	}

	private UsageService.IngestCommand command(UUID projectId) {
		return new UsageService.IngestCommand(projectId, "claude-sonnet-4-6", 1000, 2000, 1, 1, EVENT_TIME);
	}

	private void expectRejected(String json, String field) throws Exception {
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(json))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors." + field).isNotEmpty());
		assertThat(usageEventRepository.count()).isZero();
	}

	@Test
	void tokenCountsAboveTheLimitAreRejectedNamingTheField() throws Exception {
		expectRejected(ingestJson().replace("\"inputTokens\": 1000", "\"inputTokens\": 100000001"),
				"inputTokens");
		expectRejected(ingestJson().replace("\"outputTokens\": 2000", "\"outputTokens\": 100000001"),
				"outputTokens");
		expectRejected(cacheIngestJson().replace("\"cacheReadTokens\": 10000", "\"cacheReadTokens\": 100000001"),
				"cacheReadTokens");
		expectRejected(cacheIngestJson().replace("\"cacheCreationTokens\": 4000", "\"cacheCreationTokens\": 100000001"),
				"cacheCreationTokens");
	}

	@Test
	void lineCountsAboveTheLimitAreRejectedNamingTheField() throws Exception {
		expectRejected(ingestJson().replace("\"linesAdded\": 42", "\"linesAdded\": 10000001"), "linesAdded");
		expectRejected(ingestJson().replace("\"linesRemoved\": 7", "\"linesRemoved\": 10000001"), "linesRemoved");
	}

	@Test
	void modelLongerThanTheColumnIsRejectedNotA500() throws Exception {
		expectRejected(ingestJson().replace("claude-sonnet-4-6", "m".repeat(101)), "model");
	}

	@Test
	void valuesExactlyAtTheLimitsAreAccepted() throws Exception {
		modelPricingRepository.save(new ModelPricing("m".repeat(100),
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(ingestJson()
								.replace("claude-sonnet-4-6", "m".repeat(100))
								.replace("\"inputTokens\": 1000", "\"inputTokens\": 100000000")
								.replace("\"linesAdded\": 42", "\"linesAdded\": 10000000")))
				.andExpect(status().isCreated())
				// (100,000,000 * 0.003 + 2000 * 0.015) / 1000
				.andExpect(jsonPath("$.costUsd").value(300.03));
	}

	@Test
	void eventTimeInTheFutureIsRejected() throws Exception {
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(ingestJson().replace(EVENT_TIME.toString(),
								Instant.now().plusSeconds(3600).toString())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("eventTime is in the future"));
		assertThat(usageEventRepository.count()).isZero();
	}

	@Test
	void eventTimeWithinClockSkewIsAccepted() throws Exception {
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(ingestJson().replace(EVENT_TIME.toString(),
								Instant.now().plusSeconds(60).toString())))
				.andExpect(status().isCreated());
	}

	@Test
	void eventTimeOlderThanTheConfiguredAgeIsRejected() throws Exception {
		// The suite runs with app.ingest.max-event-age=P3650D
		mockMvc.perform(post("/usage")
						.header("Authorization", "Bearer " + obtainToken())
						.contentType("application/json")
						.content(ingestJson().replace(EVENT_TIME.toString(), "2000-01-01T00:00:00Z")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("eventTime is older than the accepted 3650 days"));
		assertThat(usageEventRepository.count()).isZero();
	}

	@Test
	void unavailablePricingCatalogReturns503NotTheUnknownModel422() throws Exception {
		pricingCatalog.respondWith(model -> {
			throw new PricingCatalogUnavailableException("The model pricing catalog is unavailable; retry later", null);
		});
		try {
			mockMvc.perform(post("/usage")
							.header("Authorization", "Bearer " + obtainToken())
							.contentType("application/json")
							.content(ingestJson().replace("claude-sonnet-4-6", "unknown-model")))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.detail").value("The model pricing catalog is unavailable; retry later"));
		} finally {
			pricingCatalog.reset();
		}
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
		assertThat(rollupRepository.countByGranularity(Granularity.DAY)).isEqualTo(1);

		Rollup daily = rollupRepository.findByGranularity(Granularity.DAY).getFirst();
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
		assertThat(rollupRepository.countByGranularity(Granularity.DAY)).isZero();
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
		rollupRepository.deleteByGranularity(Granularity.DAY);
		LocalDate day = LocalDate.ofInstant(EVENT_TIME, ZoneOffset.UTC);
		rollupRecomputeJob.recompute(day, day);

		Rollup daily = rollupRepository.findByGranularity(Granularity.DAY).getFirst();
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
