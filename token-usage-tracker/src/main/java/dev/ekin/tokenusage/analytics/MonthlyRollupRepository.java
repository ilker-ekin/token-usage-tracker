package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface MonthlyRollupRepository extends JpaRepository<MonthlyRollup, UUID> {

	/**
	 * Adds one event's numbers to its rollup row, creating the row if needed.
	 * A single atomic statement, so concurrent ingests for the same key neither
	 * collide on insert nor lose each other's increments.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			insert into monthly_rollup (id, org_id, team_id, member_id, project_id, month_start,
			        total_input_tokens, total_output_tokens, total_cost_usd,
			        total_lines_added, total_lines_removed)
			values (gen_random_uuid(), :orgId, cast(:teamId as uuid), :memberId, :projectId, :monthStart,
			        :inputTokens, :outputTokens, :costUsd, :linesAdded, :linesRemoved)
			on conflict (org_id, team_id, member_id, project_id, month_start) do update set
			    total_input_tokens  = monthly_rollup.total_input_tokens  + excluded.total_input_tokens,
			    total_output_tokens = monthly_rollup.total_output_tokens + excluded.total_output_tokens,
			    total_cost_usd      = monthly_rollup.total_cost_usd      + excluded.total_cost_usd,
			    total_lines_added   = monthly_rollup.total_lines_added   + excluded.total_lines_added,
			    total_lines_removed = monthly_rollup.total_lines_removed + excluded.total_lines_removed
			""")
	void upsertAdd(@Param("orgId") UUID orgId, @Param("teamId") UUID teamId,
			@Param("memberId") UUID memberId, @Param("projectId") UUID projectId,
			@Param("monthStart") LocalDate monthStart,
			@Param("inputTokens") long inputTokens, @Param("outputTokens") long outputTokens,
			@Param("costUsd") BigDecimal costUsd,
			@Param("linesAdded") long linesAdded, @Param("linesRemoved") long linesRemoved);

	/** Bulk delete so the recompute job's re-inserts flush after it, not before. */
	@Modifying
	@Query("delete from MonthlyRollup r where r.monthStart = :monthStart")
	void deleteByMonthStart(@Param("monthStart") LocalDate monthStart);

}
