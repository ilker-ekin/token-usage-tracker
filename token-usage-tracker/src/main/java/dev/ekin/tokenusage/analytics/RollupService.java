package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.usage.UsageEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
	 * transaction so the event and its rollups commit atomically. Each write
	 * is a native upsert that adds to the row in place, which makes concurrent
	 * ingests for the same member/project/period safe without explicit locking.
	 * The tables are always touched in the same order (daily, weekly, monthly),
	 * so two ingests can never deadlock on each other's rows.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void apply(UsageEvent e) {
		LocalDate day = LocalDate.ofInstant(e.getEventTime(), ZoneOffset.UTC);
		long linesAdded = e.getLinesAdded() == null ? 0 : e.getLinesAdded();
		long linesRemoved = e.getLinesRemoved() == null ? 0 : e.getLinesRemoved();

		dailyRollupRepository.upsertAdd(e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(),
				day, e.getInputTokens(), e.getOutputTokens(),
				e.getCacheReadTokens(), e.getCacheCreationTokens(),
				e.getCostUsd(), linesAdded, linesRemoved);
		weeklyRollupRepository.upsertAdd(e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(),
				day.with(DayOfWeek.MONDAY), e.getInputTokens(), e.getOutputTokens(),
				e.getCacheReadTokens(), e.getCacheCreationTokens(),
				e.getCostUsd(), linesAdded, linesRemoved);
		monthlyRollupRepository.upsertAdd(e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(),
				day.withDayOfMonth(1), e.getInputTokens(), e.getOutputTokens(),
				e.getCacheReadTokens(), e.getCacheCreationTokens(),
				e.getCostUsd(), linesAdded, linesRemoved);
	}

}
