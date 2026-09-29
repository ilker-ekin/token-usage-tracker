package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.usage.UsageEventRepository;
import dev.ekin.tokenusage.usage.UsageEventRepository.UsageAggregate;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Safety net for late-arriving events: every night the rollups covering the
 * last two days are dropped and rebuilt from usage_event, the source of
 * truth. Weeks and months touched by those days are rebuilt in full.
 *
 * <p>The rebuild takes a SHARE ROW EXCLUSIVE lock on the rollup tables. That
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
	private final DailyRollupRepository dailyRollupRepository;
	private final WeeklyRollupRepository weeklyRollupRepository;
	private final MonthlyRollupRepository monthlyRollupRepository;
	private final AnalyticsCacheService analyticsCacheService;
	private final EntityManager entityManager;

	public RollupRecomputeJob(UsageEventRepository usageEventRepository,
			DailyRollupRepository dailyRollupRepository,
			WeeklyRollupRepository weeklyRollupRepository,
			MonthlyRollupRepository monthlyRollupRepository,
			AnalyticsCacheService analyticsCacheService,
			EntityManager entityManager) {
		this.usageEventRepository = usageEventRepository;
		this.dailyRollupRepository = dailyRollupRepository;
		this.weeklyRollupRepository = weeklyRollupRepository;
		this.monthlyRollupRepository = monthlyRollupRepository;
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
		entityManager.createNativeQuery(
				"lock table daily_rollup, weekly_rollup, monthly_rollup in share row exclusive mode")
				.executeUpdate();

		Set<LocalDate> weekStarts = new LinkedHashSet<>();
		Set<LocalDate> monthStarts = new LinkedHashSet<>();

		for (LocalDate day = fromDay; !day.isAfter(toDay); day = day.plusDays(1)) {
			rebuildDay(day);
			weekStarts.add(day.with(DayOfWeek.MONDAY));
			monthStarts.add(day.withDayOfMonth(1));
		}
		weekStarts.forEach(this::rebuildWeek);
		monthStarts.forEach(this::rebuildMonth);
		analyticsCacheService.evictAll();

		log.info("Recomputed rollups for {}..{} ({} weeks, {} months)",
				fromDay, toDay, weekStarts.size(), monthStarts.size());
	}

	private void rebuildDay(LocalDate day) {
		dailyRollupRepository.deleteByRollupDate(day);
		for (UsageAggregate a : aggregate(day, day.plusDays(1))) {
			dailyRollupRepository.save(new DailyRollup(
					a.getOrgId(), a.getTeamId(), a.getMemberId(), a.getProjectId(), day,
					a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
					a.getLinesAdded(), a.getLinesRemoved()));
		}
	}

	private void rebuildWeek(LocalDate weekStart) {
		weeklyRollupRepository.deleteByWeekStart(weekStart);
		for (UsageAggregate a : aggregate(weekStart, weekStart.plusWeeks(1))) {
			weeklyRollupRepository.save(new WeeklyRollup(
					a.getOrgId(), a.getTeamId(), a.getMemberId(), a.getProjectId(), weekStart,
					a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
					a.getLinesAdded(), a.getLinesRemoved()));
		}
	}

	private void rebuildMonth(LocalDate monthStart) {
		monthlyRollupRepository.deleteByMonthStart(monthStart);
		for (UsageAggregate a : aggregate(monthStart, monthStart.plusMonths(1))) {
			monthlyRollupRepository.save(new MonthlyRollup(
					a.getOrgId(), a.getTeamId(), a.getMemberId(), a.getProjectId(), monthStart,
					a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
					a.getLinesAdded(), a.getLinesRemoved()));
		}
	}

	private Iterable<UsageAggregate> aggregate(LocalDate from, LocalDate to) {
		return usageEventRepository.aggregateBetween(
				from.atStartOfDay(ZoneOffset.UTC).toInstant(),
				to.atStartOfDay(ZoneOffset.UTC).toInstant());
	}

}