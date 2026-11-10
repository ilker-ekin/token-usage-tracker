package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.auth.JwtService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Analytics endpoints against real Postgres + Redis: correctness of the
 * org-scoped aggregates, tenant isolation, and cache hit/eviction behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnalyticsIntegrationTest {

	private static final Instant EVENT_TIME = Instant.parse("2026-06-15T10:30:00Z");
	private static final String RANGE = "from=2026-06-01&to=2026-06-30";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;
	@Autowired
	UsageService usageService;
	@Autowired
	StringRedisTemplate redisTemplate;
	@Autowired
	TransactionTemplate transactionTemplate;
	@Autowired
	RollupRecomputeJob rollupRecomputeJob;

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

	Member alice;
	Member bob;
	Member otherOrgMember;
	Project otherOrgProject;
	Project checkout;
	Project search;
	String token;

	@BeforeEach
	void setUp() {
		rollupRepository.deleteAll();
		usageEventRepository.deleteAll();
		modelPricingRepository.deleteAll();
		memberRepository.deleteAll();
		projectRepository.deleteAll();
		teamRepository.deleteAll();
		organizationRepository.deleteAll();
		Set<String> keys = redisTemplate.keys("analytics:*");
		if (!keys.isEmpty()) {
			redisTemplate.delete(keys);
		}

		Organization org = organizationRepository.save(new Organization("Org A", "org-a"));
		Team platform = teamRepository.save(new Team(org.getId(), "Platform"));
		alice = memberRepository.save(new Member(org.getId(), platform.getId(),
				"alice@a.dev", "Alice", null, "MEMBER"));
		bob = memberRepository.save(new Member(org.getId(), platform.getId(),
				"bob@a.dev", "Bob", null, "MEMBER"));
		checkout = projectRepository.save(new Project(org.getId(), "checkout"));
		search = projectRepository.save(new Project(org.getId(), "search"));
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
		token = jwtService.issueToken(alice);

		// Org B with its own activity — must never appear in Org A's numbers
		Organization orgB = organizationRepository.save(new Organization("Org B", "org-b"));
		Team teamB = teamRepository.save(new Team(orgB.getId(), "B Team"));
		otherOrgMember = memberRepository.save(new Member(orgB.getId(), teamB.getId(),
				"eve@b.dev", "Eve", null, "MEMBER"));
		otherOrgProject = projectRepository.save(new Project(orgB.getId(), "b-project"));

		// Org A: alice 2 events on checkout, bob 1 event on search
		ingest(alice, checkout, 1000, 2000); // cost 0.033
		ingest(alice, checkout, 1000, 2000); // cost 0.033
		ingest(bob, search, 2000, 1000);     // cost 0.021
		// Org B noise
		usageService.ingest(otherOrgMember.getId(), new IngestCommand(
				otherOrgProject.getId(), "claude-sonnet-4-6", 50_000, 50_000, 0, 0, EVENT_TIME));
	}

	private void ingest(Member member, Project project, int in, int out) {
		usageService.ingest(member.getId(), new IngestCommand(
				project.getId(), "claude-sonnet-4-6", in, out, 10, 5, EVENT_TIME));
	}

	@Test
	void projectAnalyticsAggregateAndExcludeOtherOrgs() throws Exception {
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("checkout"))
				.andExpect(jsonPath("$[0].inputTokens").value(2000))
				.andExpect(jsonPath("$[0].outputTokens").value(4000))
				.andExpect(jsonPath("$[0].costUsd").value(0.066))
				.andExpect(jsonPath("$[1].name").value("search"))
				.andExpect(jsonPath("$[1].costUsd").value(0.021));
	}

	@Test
	void memberAnalyticsRankTopSpendersFirst() throws Exception {
		mockMvc.perform(get("/analytics/members?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].displayName").value("Alice"))
				.andExpect(jsonPath("$[0].costUsd").value(0.066))
				.andExpect(jsonPath("$[0].linesAdded").value(20))
				.andExpect(jsonPath("$[1].displayName").value("Bob"));
	}

	@Test
	void teamAnalyticsIncludeMemberCount() throws Exception {
		mockMvc.perform(get("/analytics/teams?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Platform"))
				.andExpect(jsonPath("$[0].memberCount").value(2))
				.andExpect(jsonPath("$[0].costUsd").value(0.087));
	}

	@Test
	void dailySeriesSumsTheWholeOrg() throws Exception {
		mockMvc.perform(get("/analytics/daily?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].date").value("2026-06-15"))
				.andExpect(jsonPath("$[0].costUsd").value(0.087));
	}

	@Test
	void usageDrillDownIsOrgScopedAndFilterable() throws Exception {
		mockMvc.perform(get("/usage?page=0&size=10")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3));

		mockMvc.perform(get("/usage?memberId=" + alice.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));

		mockMvc.perform(get("/usage?projectId=" + search.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].memberId").value(bob.getId().toString()));
	}

	@Test
	void usageDrillDownIgnoresIdsFromAnotherOrg() throws Exception {
		mockMvc.perform(get("/usage?memberId=" + otherOrgMember.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));

		mockMvc.perform(get("/usage?projectId=" + otherOrgProject.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void otherOrgSeesOnlyItsOwnAnalytics() throws Exception {
		String otherToken = jwtService.issueToken(otherOrgMember);
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("b-project"));
		mockMvc.perform(get("/analytics/members?" + RANGE)
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].displayName").value("Eve"));
		mockMvc.perform(get("/usage")
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void analyticsAreCachedInRedisAndEvictedOnIngest() throws Exception {
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		Set<String> keys = redisTemplate.keys("analytics:" + alice.getOrgId() + ":*:projects:*");
		assertThat(keys).hasSize(1);

		// Served from cache: DB changes are invisible until eviction
		rollupRepository.deleteByGranularity(Granularity.DAY);
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));

		// Ingest evicts the org's keys; next read hits the (now empty) rollups
		ingest(alice, checkout, 1, 1);
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void ingestBumpsOnlyItsOwnOrgsVersionAndOnlyAfterCommit() {
		String orgVersion = AnalyticsCacheService.versionKey(alice.getOrgId());
		String otherOrgVersion = AnalyticsCacheService.versionKey(otherOrgMember.getOrgId());
		String before = redisTemplate.opsForValue().get(orgVersion);
		String otherBefore = redisTemplate.opsForValue().get(otherOrgVersion);

		// Rolled back: nothing changed, so nothing is invalidated
		transactionTemplate.executeWithoutResult(tx -> {
			ingest(alice, checkout, 1, 1);
			tx.setRollbackOnly();
		});
		assertThat(redisTemplate.opsForValue().get(orgVersion)).isEqualTo(before);

		// Committed: the bump waits for the commit, never runs before it
		transactionTemplate.executeWithoutResult(tx -> {
			ingest(alice, checkout, 1, 1);
			assertThat(redisTemplate.opsForValue().get(orgVersion)).isEqualTo(before);
		});
		assertThat(Long.parseLong(redisTemplate.opsForValue().get(orgVersion)))
				.isEqualTo(Long.parseLong(before) + 1);
		assertThat(redisTemplate.opsForValue().get(otherOrgVersion)).isEqualTo(otherBefore);
	}

	@Test
	void recomputeInvalidatesEveryOrgsCache() throws Exception {
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));

		rollupRecomputeJob.recompute(LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-15"));

		// Empty the rollups behind the job's back: a read that still hit the
		// cached two-project answer would prove the job did not invalidate it
		rollupRepository.deleteByGranularity(Granularity.DAY);
		mockMvc.perform(get("/analytics/projects?" + RANGE)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void analyticsRejectAnonymousCallers() throws Exception {
		mockMvc.perform(get("/analytics/projects?" + RANGE))
				.andExpect(status().isUnauthorized());
	}

}
