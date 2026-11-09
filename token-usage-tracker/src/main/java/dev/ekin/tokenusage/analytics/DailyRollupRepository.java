package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DailyRollupRepository extends JpaRepository<DailyRollup, UUID> {

	/**
	 * Adds one event's numbers to its rollup row, creating the row if needed.
	 * A single atomic statement, so concurrent ingests for the same key neither
	 * collide on insert nor lose each other's increments.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			insert into daily_rollup (id, org_id, team_id, member_id, project_id, rollup_date,
			        total_input_tokens, total_output_tokens,
			        total_cache_read_tokens, total_cache_creation_tokens, total_cost_usd,
			        total_lines_added, total_lines_removed)
			values (gen_random_uuid(), :orgId, cast(:teamId as uuid), :memberId, :projectId, :day,
			        :inputTokens, :outputTokens, :cacheReadTokens, :cacheCreationTokens,
			        :costUsd, :linesAdded, :linesRemoved)
			on conflict (org_id, team_id, member_id, project_id, rollup_date) do update set
			    total_input_tokens  = daily_rollup.total_input_tokens  + excluded.total_input_tokens,
			    total_output_tokens = daily_rollup.total_output_tokens + excluded.total_output_tokens,
			    total_cache_read_tokens     = daily_rollup.total_cache_read_tokens     + excluded.total_cache_read_tokens,
			    total_cache_creation_tokens = daily_rollup.total_cache_creation_tokens + excluded.total_cache_creation_tokens,
			    total_cost_usd      = daily_rollup.total_cost_usd      + excluded.total_cost_usd,
			    total_lines_added   = daily_rollup.total_lines_added   + excluded.total_lines_added,
			    total_lines_removed = daily_rollup.total_lines_removed + excluded.total_lines_removed
			""")
	void upsertAdd(@Param("orgId") UUID orgId, @Param("teamId") UUID teamId,
			@Param("memberId") UUID memberId, @Param("projectId") UUID projectId,
			@Param("day") LocalDate day,
			@Param("inputTokens") long inputTokens, @Param("outputTokens") long outputTokens,
			@Param("cacheReadTokens") long cacheReadTokens,
			@Param("cacheCreationTokens") long cacheCreationTokens,
			@Param("costUsd") BigDecimal costUsd,
			@Param("linesAdded") long linesAdded, @Param("linesRemoved") long linesRemoved);

	/** Bulk delete so the recompute job's re-inserts flush after it, not before. */
	@Modifying
	@Query("delete from DailyRollup r where r.rollupDate = :day")
	void deleteByRollupDate(@Param("day") LocalDate day);

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
			from DailyRollup r join Project p on p.id = r.projectId
			where r.orgId = :orgId and r.rollupDate between :from and :to
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
			from DailyRollup r join Member m on m.id = r.memberId
			where r.orgId = :orgId and r.rollupDate between :from and :to
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
			from DailyRollup r join Team t on t.id = r.teamId
			where r.orgId = :orgId and r.rollupDate between :from and :to
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
			select r.rollupDate as date,
			       sum(r.totalInputTokens) as inputTokens, sum(r.totalOutputTokens) as outputTokens,
			       sum(r.totalCostUsd) as costUsd
			from DailyRollup r
			where r.orgId = :orgId and r.rollupDate between :from and :to
			group by r.rollupDate
			order by r.rollupDate
			""")
	List<DailyAgg> aggregateByDay(@Param("orgId") UUID orgId,
			@Param("from") LocalDate from, @Param("to") LocalDate to);

}
