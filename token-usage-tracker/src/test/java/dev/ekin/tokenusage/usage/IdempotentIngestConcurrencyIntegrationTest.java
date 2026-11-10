package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.analytics.DailyRollup;
import dev.ekin.tokenusage.analytics.DailyRollupRepository;
import dev.ekin.tokenusage.analytics.MonthlyRollupRepository;
import dev.ekin.tokenusage.analytics.RollupBase;
import dev.ekin.tokenusage.analytics.WeeklyRollupRepository;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.ModelPricingRepository;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.TeamRepository;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import dev.ekin.tokenusage.usage.UsageService.IngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client retrying after a timeout can race its own first attempt. Many
 * requests carrying one idempotency key at once must book exactly one event
 * and count it once in every rollup, with no request failing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotentIngestConcurrencyIntegrationTest {

	private static final int REQUESTS = 50;
	private static final Instant EVENT_TIME = Instant.parse("2026-06-15T10:30:00Z");

	@Autowired
	UsageService usageService;
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

		Organization org = organizationRepository.save(new Organization("Retry Org", "retry-org"));
		member = memberRepository.save(new Member(org.getId(), null,
				"retry@retry.dev", "Retry", null, "MEMBER"));
		project = projectRepository.save(new Project(org.getId(), "retry-project"));
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
	}

	@Test
	void parallelRequestsWithOneKeyBookExactlyOneEvent() throws Exception {
		List<IngestResult> results = ingestInParallel(i -> "same-key");

		assertThat(results).hasSize(REQUESTS);
		assertThat(results.stream().filter(IngestResult::created)).hasSize(1);
		assertThat(results.stream().map(r -> r.event().getId()).distinct()).hasSize(1);

		assertThat(usageEventRepository.count()).isEqualTo(1);
		List<DailyRollup> daily = dailyRollupRepository.findAll();
		assertThat(daily).hasSize(1);
		assertOneEvent(daily.getFirst());
		assertThat(weeklyRollupRepository.findAll()).hasSize(1).allSatisfy(this::assertOneEvent);
		assertThat(monthlyRollupRepository.findAll()).hasSize(1).allSatisfy(this::assertOneEvent);
	}

	@Test
	void parallelRequestsWithDistinctKeysAllBook() throws Exception {
		List<IngestResult> results = ingestInParallel(i -> "key-" + i);

		assertThat(results).hasSize(REQUESTS).allMatch(IngestResult::created);
		assertThat(usageEventRepository.count()).isEqualTo(REQUESTS);
		DailyRollup daily = dailyRollupRepository.findAll().getFirst();
		assertThat(daily.getTotalInputTokens()).isEqualTo(1000L * REQUESTS);
		assertThat(daily.getTotalCostUsd()).isEqualByComparingTo(
				new BigDecimal("0.033").multiply(BigDecimal.valueOf(REQUESTS)));
	}

	private void assertOneEvent(RollupBase r) {
		assertThat(r.getTotalInputTokens()).isEqualTo(1000);
		assertThat(r.getTotalOutputTokens()).isEqualTo(2000);
		assertThat(r.getTotalCostUsd()).isEqualByComparingTo("0.033");
		assertThat(r.getTotalLinesAdded()).isEqualTo(1);
	}

	/** Fires all ingests at once behind a start gate; fails on any exception. */
	private List<IngestResult> ingestInParallel(IntFunction<String> keyFor) throws Exception {
		CountDownLatch ready = new CountDownLatch(REQUESTS);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
		List<IngestResult> results = Collections.synchronizedList(new ArrayList<>());

		try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
			for (int i = 0; i < REQUESTS; i++) {
				String key = keyFor.apply(i);
				pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						results.add(usageService.ingest(member.getId(), new IngestCommand(project.getId(),
								"claude-sonnet-4-6", 1000, 2000, 1, 1, EVENT_TIME), key));
					} catch (Throwable t) {
						failures.add(t);
					}
				});
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
		}

		assertThat(failures)
				.as("%d of %d ingests threw: %s", failures.size(), REQUESTS, failures.stream()
						.map(Throwable::toString).distinct().toList())
				.isEmpty();
		return results;
	}

}
