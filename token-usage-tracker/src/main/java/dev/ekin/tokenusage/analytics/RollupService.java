package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.usage.UsageEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Maintains the daily/weekly/monthly rollup tables. All periods are derived
 * from the event time in UTC.
 */
@Service
public class RollupService {

	private final DailyRollupRepository dailyRollupRepository;
	private final WeeklyRollupRepository weeklyRollupRepository;
	private final MonthlyRollupRepository monthlyRollupRepository;

	public RollupService(DailyRollupRepository dailyRollupRepository,
			WeeklyRollupRepository weeklyRollupRepository,
			MonthlyRollupRepository monthlyRollupRepository) {
		this.dailyRollupRepository = dailyRollupRepository;
		this.weeklyRollupRepository = weeklyRollupRepository;
		this.monthlyRollupRepository = monthlyRollupRepository;
	}

	/**
	 * Applies one event to all three rollups. Runs inside the ingest
	 * transaction so the event and its rollups commit atomically.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void apply(UsageEvent event) {
		LocalDate day = LocalDate.ofInstant(event.getEventTime(), ZoneOffset.UTC);
		long linesAdded = event.getLinesAdded() == null ? 0 : event.getLinesAdded();
		long linesRemoved = event.getLinesRemoved() == null ? 0 : event.getLinesRemoved();

		applyDaily(event, day, linesAdded, linesRemoved);
		applyWeekly(event, day.with(DayOfWeek.MONDAY), linesAdded, linesRemoved);
		applyMonthly(event, day.withDayOfMonth(1), linesAdded, linesRemoved);
	}

	private void applyDaily(UsageEvent e, LocalDate day, long linesAdded, long linesRemoved) {
		DailyRollup rollup = dailyRollupRepository
				.findByOrgIdAndTeamIdAndMemberIdAndProjectIdAndRollupDate(
						e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(), day)
				.orElseGet(() -> dailyRollupRepository.save(new DailyRollup(
						e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(), day,
						0, 0, BigDecimal.ZERO, 0, 0)));
		rollup.accumulate(e.getInputTokens(), e.getOutputTokens(), e.getCostUsd(),
				linesAdded, linesRemoved);
	}

	private void applyWeekly(UsageEvent e, LocalDate weekStart, long linesAdded, long linesRemoved) {
		WeeklyRollup rollup = weeklyRollupRepository
				.findByOrgIdAndTeamIdAndMemberIdAndProjectIdAndWeekStart(
						e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(), weekStart)
				.orElseGet(() -> weeklyRollupRepository.save(new WeeklyRollup(
						e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(), weekStart,
						0, 0, BigDecimal.ZERO, 0, 0)));
		rollup.accumulate(e.getInputTokens(), e.getOutputTokens(), e.getCostUsd(),
				linesAdded, linesRemoved);
	}

	private void applyMonthly(UsageEvent e, LocalDate monthStart, long linesAdded, long linesRemoved) {
		MonthlyRollup rollup = monthlyRollupRepository
				.findByOrgIdAndTeamIdAndMemberIdAndProjectIdAndMonthStart(
						e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(), monthStart)
				.orElseGet(() -> monthlyRollupRepository.save(new MonthlyRollup(
						e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(), monthStart,
						0, 0, BigDecimal.ZERO, 0, 0)));
		rollup.accumulate(e.getInputTokens(), e.getOutputTokens(), e.getCostUsd(),
				linesAdded, linesRemoved);
	}

}