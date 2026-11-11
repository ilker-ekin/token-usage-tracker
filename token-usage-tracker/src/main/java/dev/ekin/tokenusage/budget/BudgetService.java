package dev.ekin.tokenusage.budget;

import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Budget CRUD, always scoped to the caller's org. */
@Service
public class BudgetService {

	private final BudgetRepository budgetRepository;
	private final BudgetAlertRepository budgetAlertRepository;
	private final ProjectRepository projectRepository;
	private final TeamRepository teamRepository;
	private final WebhookUrlValidator webhookUrlValidator;

	public BudgetService(BudgetRepository budgetRepository, BudgetAlertRepository budgetAlertRepository,
			ProjectRepository projectRepository, TeamRepository teamRepository,
			WebhookUrlValidator webhookUrlValidator) {
		this.budgetRepository = budgetRepository;
		this.budgetAlertRepository = budgetAlertRepository;
		this.projectRepository = projectRepository;
		this.teamRepository = teamRepository;
		this.webhookUrlValidator = webhookUrlValidator;
	}

	/** A budget together with what its scope has spent so far this month. */
	public record BudgetWithSpend(Budget budget, BigDecimal currentSpendUsd) {
	}

	static LocalDate currentMonthStart() {
		return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
	}

	@Transactional
	public BudgetWithSpend create(UUID orgId, BudgetScope scopeType, UUID scopeId,
			BigDecimal monthlyLimitUsd, String webhookUrl, boolean enabled) {
		boolean scopeInOrg = switch (scopeType) {
			case PROJECT -> projectRepository.findByIdAndOrgId(scopeId, orgId).isPresent();
			case TEAM -> teamRepository.findByIdAndOrgId(scopeId, orgId).isPresent();
		};
		if (!scopeInOrg) {
			throw new BudgetScopeNotFoundException(scopeType, scopeId);
		}
		webhookUrlValidator.validate(webhookUrl);
		if (budgetRepository.existsByOrgIdAndScopeTypeAndScopeId(orgId, scopeType, scopeId)) {
			throw new DuplicateBudgetException();
		}
		return withSpend(budgetRepository.save(
				new Budget(orgId, scopeType, scopeId, monthlyLimitUsd, webhookUrl, enabled)));
	}

	@Transactional(readOnly = true)
	public List<BudgetWithSpend> list(UUID orgId) {
		return budgetRepository.findByOrgIdOrderByCreatedAt(orgId).stream().map(this::withSpend).toList();
	}

	/**
	 * Alerts already raised this month stay as they are: raising the limit
	 * does not retract one, and lowering it only produces alerts for
	 * thresholds that have none yet.
	 */
	@Transactional
	public BudgetWithSpend update(UUID orgId, UUID budgetId, BigDecimal monthlyLimitUsd,
			String webhookUrl, boolean enabled) {
		Budget budget = find(orgId, budgetId);
		webhookUrlValidator.validate(webhookUrl);
		budget.update(monthlyLimitUsd, webhookUrl, enabled);
		return withSpend(budget);
	}

	/** Deletes the budget and, through the foreign key, its alert history. */
	@Transactional
	public void delete(UUID orgId, UUID budgetId) {
		budgetRepository.delete(find(orgId, budgetId));
	}

	/** Gives the budget a new signing secret and returns it; later deliveries use it. */
	@Transactional
	public String rotateWebhookSecret(UUID orgId, UUID budgetId) {
		return find(orgId, budgetId).rotateWebhookSecret();
	}

	@Transactional(readOnly = true)
	public List<BudgetAlert> alerts(UUID orgId, UUID budgetId) {
		find(orgId, budgetId);
		return budgetAlertRepository.findByBudgetIdOrderByPeriodStartDescThresholdPctDesc(budgetId);
	}

	private Budget find(UUID orgId, UUID budgetId) {
		return budgetRepository.findByIdAndOrgId(budgetId, orgId)
				.orElseThrow(() -> new BudgetNotFoundException(budgetId));
	}

	private BudgetWithSpend withSpend(Budget budget) {
		return new BudgetWithSpend(budget,
				budgetRepository.spendInMonth(budget.getId(), currentMonthStart()));
	}

}
