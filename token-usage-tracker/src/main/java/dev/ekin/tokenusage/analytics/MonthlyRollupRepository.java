package dev.ekin.tokenusage.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface MonthlyRollupRepository extends JpaRepository<MonthlyRollup, UUID> {

	Optional<MonthlyRollup> findByOrgIdAndTeamIdAndMemberIdAndProjectIdAndMonthStart(
			UUID orgId, UUID teamId, UUID memberId, UUID projectId, LocalDate monthStart);

	/** Bulk delete so the recompute job's re-inserts flush after it, not before. */
	@Modifying
	@Query("delete from MonthlyRollup r where r.monthStart = :monthStart")
	void deleteByMonthStart(@Param("monthStart") LocalDate monthStart);

}
