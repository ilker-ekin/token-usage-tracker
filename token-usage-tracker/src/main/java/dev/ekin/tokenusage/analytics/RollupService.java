package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.usage.UsageEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Maintains the rollup table. All periods are derived from the event time
 * in UTC.
 */
@Service
public class RollupService {

	private final RollupRepository rollupRepository;

	public RollupService(RollupRepository rollupRepository) {
		this.rollupRepository = rollupRepository;
	}

	/**
	 * Adds one event to its day, week and month rollups. Runs inside the
	 * ingest transaction so the event and its rollups commit atomically. The
	 * write is one native upsert that adds to the rows in place, which makes
	 * concurrent ingests for the same member/project/period safe without
	 * explicit locking.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void apply(UsageEvent e) {
		LocalDate day = LocalDate.ofInstant(e.getEventTime(), ZoneOffset.UTC);
		long linesAdded = e.getLinesAdded() == null ? 0 : e.getLinesAdded();
		long linesRemoved = e.getLinesRemoved() == null ? 0 : e.getLinesRemoved();

		rollupRepository.upsertAdd(e.getOrgId(), e.getTeamId(), e.getMemberId(), e.getProjectId(),
				e.getModel(), Granularity.DAY.periodStart(day), Granularity.WEEK.periodStart(day),
				Granularity.MONTH.periodStart(day),
				e.getInputTokens(), e.getOutputTokens(),
				e.getCacheReadTokens(), e.getCacheCreationTokens(),
				e.getCostUsd(), linesAdded, linesRemoved);
	}

}
