package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.TestcontainersConfiguration;
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
import dev.ekin.tokenusage.usage.UsageEvent;
import dev.ekin.tokenusage.usage.UsageEventRepository;
import dev.ekin.tokenusage.usage.UsageService;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many ingests for the same (org, team, member, project, period) at once:
 * every rollup must end up as exactly one row holding the exact sum. Covers
 * members with and without a team, since team_id is nullable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RollupConcurrencyIntegrationTest {

	private static final int EVENTS = 50;
	private static final Instant EVENT_TIME = Instant.parse("2026-06-15T10:30:00Z");
	private static final LocalDate DAY = LocalDate.ofInstant(EVENT_TIME, ZoneOffset.UTC);

	@Autowired
	UsageService usageService;
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

	Member teamMember;
	Member teamlessMember;
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

		Organization org = organizationRepository.save(new Organization("Race Org", "race-org"));
		Team team = teamRepository.save(new Team(org.getId(), "Racers"));
		teamMember = memberRepository.save(new Member(org.getId(), team.getId(),
				"racer@race.dev", "Racer", null, "MEMBER"));
		teamlessMember = memberRepository.save(new Member(org.getId(), null,
				"solo@race.dev", "Solo", null, "MEMBER"));
		project = projectRepository.save(new Project(org.getId(), "race-project"));
		// $3/M input, $15/M output, $0.30/M cache read, $3.75/M cache write:
		// every event's cost is exact at scale 6
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				new BigDecimal("0.0003"), new BigDecimal("0.00375"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
	}

	@Test
	void parallelIngestsForMemberWithTeamSumExactly() throws Exception {
		ingestInParallel(teamMember);
		assertRollupsHoldExactSum();
	}

	@Test
	void parallelIngestsForMemberWithoutTeamSumExactly() throws Exception {
		ingestInParallel(teamlessMember);
		assertRollupsHoldExactSum();
	}

	@Test
	void recomputeAfterParallelIngestsReproducesTheSameTotals() throws Exception {
		ingestInParallel(teamMember);
		ingestInParallel(teamlessMember);

		rollupRecomputeJob.recompute(DAY, DAY);

		assertThat(rollupRepository.countByGranularity(Granularity.DAY)).isEqualTo(2);
		assertThat(rollupRepository.countByGranularity(Granularity.WEEK)).isEqualTo(2);
		assertThat(rollupRepository.countByGranularity(Granularity.MONTH)).isEqualTo(2);
		for (Rollup r : rollupRepository.findByGranularity(Granularity.DAY)) {
			assertTotals(r);
		}
	}

	/**
	 * The nightly job deletes and rebuilds a day's rows while ingests keep
	 * arriving. Each round books against a fresh project, so every rollup key
	 * is new while the job runs: the case where the job's re-insert and an
	 * ingest's upsert race to create the same row.
	 */
	@Test
	void recomputeRunningDuringIngestsNeitherFailsNorLosesEvents() throws Exception {
		int rounds = 10;
		for (int round = 0; round < rounds; round++) {
			Project fresh = projectRepository.save(new Project(project.getOrgId(), "p" + round));
			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService pool = Executors.newFixedThreadPool(EVENTS + 1)) {
				pool.submit(() -> {
					try {
						start.await();
						rollupRecomputeJob.recompute(DAY, DAY);
					} catch (Throwable t) {
						failures.add(t);
					}
				});
				for (int i = 0; i < EVENTS; i++) {
					int n = i;
					Member member = n % 2 == 0 ? teamMember : teamlessMember;
					pool.submit(() -> {
						try {
							start.await();
							usageService.ingest(member.getId(), new IngestCommand(fresh.getId(),
									"claude-sonnet-4-6", inputTokens(n), outputTokens(n),
									cacheReadTokens(n), cacheCreationTokens(n), 1, 1, EVENT_TIME));
						} catch (Throwable t) {
							failures.add(t);
						}
					});
				}
				start.countDown();
			}
			assertThat(failures)
					.as("round %d: %s", round, failures.stream().map(Throwable::toString).distinct().toList())
					.isEmpty();
		}

		// Every committed event is reflected exactly once, in one row per key
		long eventInput = usageEventRepository.findAll().stream()
				.mapToLong(UsageEvent::getInputTokens).sum();
		assertThat(rollupRepository.countByGranularity(Granularity.DAY)).isEqualTo(2L * rounds);
		assertThat(rollupRepository.findByGranularity(Granularity.DAY).stream()
				.mapToLong(Rollup::getTotalInputTokens).sum()).isEqualTo(eventInput);
		assertThat(rollupRepository.findByGranularity(Granularity.MONTH).stream()
				.mapToLong(Rollup::getTotalInputTokens).sum()).isEqualTo(eventInput);
	}

	/** Fires all ingests at once behind a start gate; fails on any exception. */
	private void ingestInParallel(Member member) throws Exception {
		CountDownLatch ready = new CountDownLatch(EVENTS);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		try (ExecutorService pool = Executors.newFixedThreadPool(EVENTS)) {
			for (int i = 0; i < EVENTS; i++) {
				int n = i;
				pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						usageService.ingest(member.getId(), new IngestCommand(project.getId(),
								"claude-sonnet-4-6", inputTokens(n), outputTokens(n),
								cacheReadTokens(n), cacheCreationTokens(n), 1, 1, EVENT_TIME));
					} catch (Throwable t) {
						failures.add(t);
					}
				});
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
		}

		assertThat(failures)
				.as("%d of %d ingests threw: %s", failures.size(), EVENTS, failures.stream()
						.map(t -> t.getClass().getSimpleName()).distinct().toList())
				.isEmpty();
	}

	private void assertRollupsHoldExactSum() {
		assertThat(usageEventRepository.count()).isEqualTo(EVENTS);

		List<Rollup> daily = rollupRepository.findByGranularity(Granularity.DAY);
		assertThat(daily).hasSize(1);
		assertThat(daily.getFirst().getPeriodStart()).isEqualTo(DAY);
		assertTotals(daily.getFirst());

		List<Rollup> weekly = rollupRepository.findByGranularity(Granularity.WEEK);
		assertThat(weekly).hasSize(1);
		assertThat(weekly.getFirst().getPeriodStart()).isEqualTo(DAY.with(DayOfWeek.MONDAY));
		assertTotals(weekly.getFirst());

		List<Rollup> monthly = rollupRepository.findByGranularity(Granularity.MONTH);
		assertThat(monthly).hasSize(1);
		assertThat(monthly.getFirst().getPeriodStart()).isEqualTo(DAY.withDayOfMonth(1));
		assertTotals(monthly.getFirst());
	}

	private static void assertTotals(Rollup r) {
		long expectedIn = 0;
		long expectedOut = 0;
		long expectedCacheRead = 0;
		long expectedCacheCreation = 0;
		BigDecimal expectedCost = BigDecimal.ZERO;
		for (int i = 0; i < EVENTS; i++) {
			expectedIn += inputTokens(i);
			expectedOut += outputTokens(i);
			expectedCacheRead += cacheReadTokens(i);
			expectedCacheCreation += cacheCreationTokens(i);
			// Micro-dollars per event: in*3 + out*15 + read*0.3 + write*3.75
			expectedCost = expectedCost.add(BigDecimal.valueOf(
					inputTokens(i) * 3L + outputTokens(i) * 15L
							+ cacheReadTokens(i) * 3L / 10 + cacheCreationTokens(i) * 15L / 4, 6));
		}
		assertThat(r.getTotalInputTokens()).isEqualTo(expectedIn);
		assertThat(r.getTotalOutputTokens()).isEqualTo(expectedOut);
		assertThat(r.getTotalCacheReadTokens()).isEqualTo(expectedCacheRead);
		assertThat(r.getTotalCacheCreationTokens()).isEqualTo(expectedCacheCreation);
		assertThat(r.getTotalCostUsd()).isEqualByComparingTo(expectedCost);
		assertThat(r.getTotalLinesAdded()).isEqualTo(EVENTS);
		assertThat(r.getTotalLinesRemoved()).isEqualTo(EVENTS);
	}

	/** Distinct per event so a lost update always shows up in the sum. */
	private static int inputTokens(int i) {
		return 1000 + i;
	}

	private static int outputTokens(int i) {
		return 500 + 7 * i;
	}

	/** Multiples of 10 so the $0.30/M read price yields whole micro-dollars. */
	private static int cacheReadTokens(int i) {
		return 200 + 10 * i;
	}

	/** Multiples of 4 so the $3.75/M write price yields whole micro-dollars. */
	private static int cacheCreationTokens(int i) {
		return 100 + 4 * i;
	}

}
