package dev.ekin.tokenusage.budget;

import dev.ekin.tokenusage.auth.MemberPrincipal;
import dev.ekin.tokenusage.budget.BudgetService.BudgetWithSpend;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only budget management. The org always comes from the JWT; a budget,
 * project or team of another org is reported as not found.
 */
@RestController
@RequestMapping("/budgets")
@PreAuthorize("hasRole('ADMIN')")
public class BudgetController {

	private final BudgetService budgetService;

	public BudgetController(BudgetService budgetService) {
		this.budgetService = budgetService;
	}

	public record CreateBudgetRequest(
			@NotNull BudgetScope scopeType,
			@NotNull UUID scopeId,
			@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal monthlyLimitUsd,
			@NotBlank @Size(max = 2000) String webhookUrl,
			Boolean enabled) {
	}

	public record UpdateBudgetRequest(
			@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal monthlyLimitUsd,
			@NotBlank @Size(max = 2000) String webhookUrl,
			@NotNull Boolean enabled) {
	}

	public record BudgetResponse(UUID id, BudgetScope scopeType, UUID scopeId, BigDecimal monthlyLimitUsd,
			String webhookUrl, boolean enabled, BigDecimal currentSpendUsd, Instant createdAt) {

		static BudgetResponse from(BudgetWithSpend b) {
			Budget budget = b.budget();
			return new BudgetResponse(budget.getId(), budget.getScopeType(), budget.getScopeId(),
					budget.getMonthlyLimitUsd(), budget.getWebhookUrl(), budget.isEnabled(),
					b.currentSpendUsd(), budget.getCreatedAt());
		}
	}

	public record AlertResponse(UUID id, LocalDate periodStart, int thresholdPct, BigDecimal spendUsd,
			BigDecimal limitUsd, BudgetAlert.Status status, int attempts, Instant createdAt, Instant sentAt) {

		static AlertResponse from(BudgetAlert a) {
			return new AlertResponse(a.getId(), a.getPeriodStart(), a.getThresholdPct(), a.getSpendUsd(),
					a.getLimitUsd(), a.getStatus(), a.getAttempts(), a.getCreatedAt(), a.getSentAt());
		}
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public BudgetResponse create(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestBody @Validated CreateBudgetRequest request) {
		return BudgetResponse.from(budgetService.create(principal.orgId(), request.scopeType(),
				request.scopeId(), request.monthlyLimitUsd(), request.webhookUrl(),
				request.enabled() == null || request.enabled()));
	}

	@GetMapping
	public List<BudgetResponse> list(@AuthenticationPrincipal MemberPrincipal principal) {
		return budgetService.list(principal.orgId()).stream().map(BudgetResponse::from).toList();
	}

	@PutMapping("/{id}")
	public BudgetResponse update(@AuthenticationPrincipal MemberPrincipal principal, @PathVariable UUID id,
			@RequestBody @Validated UpdateBudgetRequest request) {
		return BudgetResponse.from(budgetService.update(principal.orgId(), id,
				request.monthlyLimitUsd(), request.webhookUrl(), request.enabled()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal MemberPrincipal principal, @PathVariable UUID id) {
		budgetService.delete(principal.orgId(), id);
	}

	@GetMapping("/{id}/alerts")
	public List<AlertResponse> alerts(@AuthenticationPrincipal MemberPrincipal principal,
			@PathVariable UUID id) {
		return budgetService.alerts(principal.orgId(), id).stream().map(AlertResponse::from).toList();
	}

}
