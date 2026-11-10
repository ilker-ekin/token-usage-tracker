package dev.ekin.tokenusage.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BudgetAlertRepository extends JpaRepository<BudgetAlert, UUID> {

	List<BudgetAlert> findByBudgetIdOrderByPeriodStartDescThresholdPctDesc(UUID budgetId);

	/**
	 * Raises an alert for every enabled budget and threshold (80, 100) whose
	 * month-to-date spend has reached that share of the limit and that has no
	 * alert for the month yet. Returns the number of alerts created.
	 *
	 * <p>One statement on purpose. The unique key on (budget, month,
	 * threshold) makes it idempotent: running it again, on another instance or
	 * after a restart, inserts nothing for a threshold already alerted. A
	 * budget that jumps from below 80% to above 100% between two runs gets
	 * both rows.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
			insert into budget_alert (id, budget_id, period_start, threshold_pct,
			        spend_usd, limit_usd, status, attempts)
			select gen_random_uuid(), b.id, :monthStart, t.pct, s.spend, b.monthly_limit_usd, 'PENDING', 0
			from budget b
			cross join (values (80), (100)) as t(pct)
			cross join lateral (
			    select coalesce(sum(r.total_cost_usd), 0) as spend
			    from rollup r
			    where r.org_id = b.org_id and r.granularity = 'MONTH' and r.period_start = :monthStart
			      and ((b.scope_type = 'PROJECT' and r.project_id = b.scope_id)
			        or (b.scope_type = 'TEAM' and r.team_id = b.scope_id))
			) s
			where b.enabled
			  and s.spend * 100 >= b.monthly_limit_usd * t.pct
			on conflict (budget_id, period_start, threshold_pct) do nothing
			""")
	int raiseCrossedThresholds(@Param("monthStart") LocalDate monthStart);

	/**
	 * Claims up to {@code batchSize} undelivered alerts for this caller and
	 * returns their ids. SKIP LOCKED keeps two senders from claiming the same
	 * row. A SENDING row whose claim is older than {@code staleSeconds} belongs
	 * to a sender that died mid-delivery and is claimed again.
	 */
	@Query(nativeQuery = true, value = """
			update budget_alert set status = 'SENDING', claimed_at = now()
			where id in (
			    select id from budget_alert
			    where status = 'PENDING'
			       or (status = 'SENDING' and claimed_at < now() - make_interval(secs => :staleSeconds))
			    order by created_at
			    limit :batchSize
			    for update skip locked)
			returning id
			""")
	List<UUID> claimUndelivered(@Param("batchSize") int batchSize, @Param("staleSeconds") long staleSeconds);

}
