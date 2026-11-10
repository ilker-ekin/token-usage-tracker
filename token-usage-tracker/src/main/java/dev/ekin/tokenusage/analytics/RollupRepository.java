package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface RollupRepository extends JpaRepository<Rollup, UUID> {

	List<Rollup> findByGranularity(Granularity granularity);

	long countByGranularity(Granularity granularity);

	@Transactional
	@Modifying
	@Query("delete from Rollup r where r.granularity = :granularity")
	void deleteByGranularity(@Param("granularity") Granularity granularity);

	/**
	 * Adds one event's numbers to its day, week and month rows, creating
	 * whichever do not exist yet. A single atomic statement, so concurrent
	 * ingests for the same key neither collide on insert nor lose each
	 * other's increments.
	 *
	 * <p>The rows are always written in the order day, week, month. Two
	 * ingests therefore take their row locks in the same order and cannot
	 * deadlock on each other.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			insert into rollup (id, org_id, team_id, member_id, project_id, granularity, period_start,
			        total_input_tokens, total_output_tokens,
			        total_cache_read_tokens, total_cache_creation_tokens, total_cost_usd,
			        total_lines_added, total_lines_removed)
			values (gen_random_uuid(), :orgId, cast(:teamId as uuid), :memberId, :projectId, 'DAY', :day,
			        :inputTokens, :outputTokens, :cacheReadTokens, :cacheCreationTokens,
			        :costUsd, :linesAdded, :linesRemoved),
			       (gen_random_uuid(), :orgId, cast(:teamId as uuid), :memberId, :projectId, 'WEEK', :weekStart,
			        :inputTokens, :outputTokens, :cacheReadTokens, :cacheCreationTokens,
			        :costUsd, :linesAdded, :linesRemoved),
			       (gen_random_uuid(), :orgId, cast(:teamId as uuid), :memberId, :projectId, 'MONTH', :monthStart,
			        :inputTokens, :outputTokens, :cacheReadTokens, :cacheCreationTokens,
			        :costUsd, :linesAdded, :linesRemoved)
			on conflict (org_id, team_id, member_id, project_id, granularity, period_start) do update set
			    total_input_tokens          = rollup.total_input_tokens          + excluded.total_input_tokens,
			    total_output_tokens         = rollup.total_output_tokens         + excluded.total_output_tokens,
			    total_cache_read_tokens     = rollup.total_cache_read_tokens     + excluded.total_cache_read_tokens,
			    total_cache_creation_tokens = rollup.total_cache_creation_tokens + excluded.total_cache_creation_tokens,
			    total_cost_usd              = rollup.total_cost_usd              + excluded.total_cost_usd,
			    total_lines_added           = rollup.total_lines_added           + excluded.total_lines_added,
			    total_lines_removed         = rollup.total_lines_removed         + excluded.total_lines_removed
			""")
	void upsertAdd(@Param("orgId") UUID orgId, @Param("teamId") UUID teamId,
			@Param("memberId") UUID memberId, @Param("projectId") UUID projectId,
			@Param("day") LocalDate day, @Param("weekStart") LocalDate weekStart,
			@Param("monthStart") LocalDate monthStart,
			@Param("inputTokens") long inputTokens, @Param("outputTokens") long outputTokens,
			@Param("cacheReadTokens") long cacheReadTokens,
			@Param("cacheCreationTokens") long cacheCreationTokens,
			@Param("costUsd") BigDecimal costUsd,
			@Param("linesAdded") long linesAdded, @Param("linesRemoved") long linesRemoved);

	/** Bulk delete so the recompute job's re-inserts flush after it, not before. */
	@Modifying
	@Query("delete from Rollup r where r.granularity = :granularity and r.periodStart = :periodStart")
	void deleteByGranularityAndPeriodStart(@Param("granularity") Granularity granularity,
			@Param("periodStart") LocalDate periodStart);

	// The dashboard aggregates daily rows only: a date range rarely lines up
	// with whole weeks or months.

	interface ProjectAgg {
		UUID getProjectId();
		String getName();
		long getInputTokens();
		long getOutputTokens();
		BigDecimal getCostUsd();
		long getLinesAdded();
		long getLinesRemoved();
	}

	@Query("""
			select r.projectId as projectId, p.name as name,
			       sum(r.totalInputTokens) as inputTokens, sum(r.totalOutputTokens) as outputTokens,
			       sum(r.totalCostUsd) as costUsd,
			       sum(r.totalLinesAdded) as linesAdded, sum(r.totalLinesRemoved) as linesRemoved
			from Rollup r join Project p on p.id = r.projectId
			where r.orgId = :orgId and r.granularity = dev.ekin.tokenusage.analytics.Granularity.DAY
			  and r.periodStart between :from and :to
			group by r.projectId, p.name
			order by sum(r.totalCostUsd) desc
			""")
	List<ProjectAgg> aggregateByProject(@Param("orgId") UUID orgId,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

	interface MemberAgg {
		UUID getMemberId();
		String getDisplayName();
		String getEmail();
		long getInputTokens();
		long getOutputTokens();
		BigDecimal getCostUsd();
		long getLinesAdded();
		long getLinesRemoved();
	}

	@Query("""
			select r.memberId as memberId, m.displayName as displayName, m.email as email,
			       sum(r.totalInputTokens) as inputTokens, sum(r.totalOutputTokens) as outputTokens,
			       sum(r.totalCostUsd) as costUsd,
			       sum(r.totalLinesAdded) as linesAdded, sum(r.totalLinesRemoved) as linesRemoved
			from Rollup r join Member m on m.id = r.memberId
			where r.orgId = :orgId and r.granularity = dev.ekin.tokenusage.analytics.Granularity.DAY
			  and r.periodStart between :from and :to
			group by r.memberId, m.displayName, m.email
			order by sum(r.totalCostUsd) desc
			""")
	List<MemberAgg> aggregateByMember(@Param("orgId") UUID orgId,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

	interface TeamAgg {
		UUID getTeamId();
		String getName();
		long getInputTokens();
		long getOutputTokens();
		BigDecimal getCostUsd();
		long getLinesAdded();
		long getLinesRemoved();
	}

	@Query("""
			select r.teamId as teamId, t.name as name,
			       sum(r.totalInputTokens) as inputTokens, sum(r.totalOutputTokens) as outputTokens,
			       sum(r.totalCostUsd) as costUsd,
			       sum(r.totalLinesAdded) as linesAdded, sum(r.totalLinesRemoved) as linesRemoved
			from Rollup r join Team t on t.id = r.teamId
			where r.orgId = :orgId and r.granularity = dev.ekin.tokenusage.analytics.Granularity.DAY
			  and r.periodStart between :from and :to
			group by r.teamId, t.name
			order by sum(r.totalCostUsd) desc
			""")
	List<TeamAgg> aggregateByTeam(@Param("orgId") UUID orgId,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

	interface DailyAgg {
		LocalDate getDate();
		long getInputTokens();
		long getOutputTokens();
		BigDecimal getCostUsd();
	}

	@Query("""
			select r.periodStart as date,
			       sum(r.totalInputTokens) as inputTokens, sum(r.totalOutputTokens) as outputTokens,
			       sum(r.totalCostUsd) as costUsd
			from Rollup r
			where r.orgId = :orgId and r.granularity = dev.ekin.tokenusage.analytics.Granularity.DAY
			  and r.periodStart between :from and :to
			group by r.periodStart
			order by r.periodStart
			""")
	List<DailyAgg> aggregateByDay(@Param("orgId") UUID orgId,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

}
