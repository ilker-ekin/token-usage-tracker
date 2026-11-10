package dev.ekin.tokenusage.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

	List<Budget> findByOrgIdOrderByCreatedAt(UUID orgId);

	Optional<Budget> findByIdAndOrgId(UUID id, UUID orgId);

	boolean existsByOrgIdAndScopeTypeAndScopeId(UUID orgId, BudgetScope scopeType, UUID scopeId);

	/**
	 * What the budget's scope has spent in the given month, from the monthly
	 * rollup. A team budget counts events by the team recorded on the event at
	 * ingest, so spend by members without a team counts toward no team budget.
	 */
	@Query(nativeQuery = true, value = """
			select coalesce(sum(r.total_cost_usd), 0)
			from budget b
			left join monthly_rollup r
			       on r.org_id = b.org_id and r.month_start = :monthStart
			      and ((b.scope_type = 'PROJECT' and r.project_id = b.scope_id)
			        or (b.scope_type = 'TEAM' and r.team_id = b.scope_id))
			where b.id = :budgetId
			""")
	BigDecimal spendInMonth(@Param("budgetId") UUID budgetId, @Param("monthStart") LocalDate monthStart);

}
