package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyRollupRepository extends JpaRepository<DailyRollup, UUID> {

	Optional<DailyRollup> findByOrgIdAndTeamIdAndMemberIdAndProjectIdAndRollupDate(
			UUID orgId, UUID teamId, UUID memberId, UUID projectId, LocalDate rollupDate);

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
