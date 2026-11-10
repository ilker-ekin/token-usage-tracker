package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.usage.UsageEventRepository;
import dev.ekin.tokenusage.usage.UsageEventRepository.UsageAggregate;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Safety net for late-arriving events: every night the rollups covering the
 * last two days are dropped and rebuilt from usage_event, the source of
 * truth. Weeks and months touched by those days are rebuilt in full.
 *
 * <p>The rebuild takes a SHARE ROW EXCLUSIVE lock on the rollup table. That
 * mode conflicts with the ROW EXCLUSIVE lock every ingest upsert takes, so the
 * job waits for in-flight ingests to commit and new ones queue behind it for
 * the few seconds it runs. Without it an ingest could create a rollup row
 * between the job's delete and re-insert, failing the job on the unique key.
 * Dashboard reads (ACCESS SHARE) are not blocked.
 */
@Component
public class RollupRecomputeJob {

	private static final Logger log = LoggerFactory.getLogger(RollupRecomputeJob.class);

	private final UsageEventRepository usageEventRepository;
	private final RollupRepository rollupRepository;
	private final AnalyticsCacheService analyticsCacheService;
	private final EntityManager entityManager;

	public RollupRecomputeJob(UsageEventRepository usageEventRepository,
			RollupRepository rollupRepository,
			AnalyticsCacheService analyticsCacheService,
			EntityManager entityManager) {
		this.usageEventRepository = usageEventRepository;
		this.rollupRepository = rollupRepository;
		this.analyticsCacheService = analyticsCacheService;
		this.entityManager = entityManager;
	}

	@Scheduled(cron = "0 30 2 * * *", zone = "UTC")
	public void recomputeRecent() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		recompute(today.minusDays(1), today);
	}

	@Transactional
	public void recompute(LocalDate fromDay, LocalDate toDay) {
		entityManager.createNativeQuery("lock table rollup in share row exclusive mode")
				.executeUpdate();

		// Every period, of every granularity, that contains one of the days
		Map<Granularity, Set<LocalDate>> periods = new EnumMap<>(Granularity.class);
		for (Granularity granularity : Granularity.values()) {
			Set<LocalDate> starts = new LinkedHashSet<>();
			for (LocalDate day = fromDay; !day.isAfter(toDay); day = day.plusDays(1)) {
				starts.add(granularity.periodStart(day));
			}
			periods.put(granularity, starts);
		}
		periods.forEach((granularity, starts) ->
				starts.forEach(start -> rebuild(granularity, start)));
		analyticsCacheService.evictAll();

		log.info("Recomputed rollups for {}..{} ({} weeks, {} months)", fromDay, toDay,
				periods.get(Granularity.WEEK).size(), periods.get(Granularity.MONTH).size());
	}

	private void rebuild(Granularity granularity, LocalDate periodStart) {
		rollupRepository.deleteByGranularityAndPeriodStart(granularity, periodStart);
		Iterable<UsageAggregate> aggregates = usageEventRepository.aggregateBetween(
				periodStart.atStartOfDay(ZoneOffset.UTC).toInstant(),
				granularity.nextPeriodStart(periodStart).atStartOfDay(ZoneOffset.UTC).toInstant());
		for (UsageAggregate a : aggregates) {
			rollupRepository.save(new Rollup(
					a.getOrgId(), a.getTeamId(), a.getMemberId(), a.getProjectId(), a.getModel(),
					granularity, periodStart,
					a.getInputTokens(), a.getOutputTokens(),
					a.getCacheReadTokens(), a.getCacheCreationTokens(), a.getCostUsd(),
					a.getLinesAdded(), a.getLinesRemoved()));
		}
	}

}
