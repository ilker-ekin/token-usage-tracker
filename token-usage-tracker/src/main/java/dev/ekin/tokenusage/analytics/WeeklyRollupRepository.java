package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyRollupRepository extends JpaRepository<WeeklyRollup, UUID> {

	Optional<WeeklyRollup> findByOrgIdAndTeamIdAndMemberIdAndProjectIdAndWeekStart(
			UUID orgId, UUID teamId, UUID memberId, UUID projectId, LocalDate weekStart);

	/** Bulk delete so the recompute job's re-inserts flush after it, not before. */
	@Modifying
	@Query("delete from WeeklyRollup r where r.weekStart = :weekStart")
	void deleteByWeekStart(@Param("weekStart") LocalDate weekStart);

}
