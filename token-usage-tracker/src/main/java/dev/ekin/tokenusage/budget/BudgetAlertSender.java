package dev.ekin.tokenusage.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Delivers raised alerts to their budget's webhook. Three steps per batch,
 * and the HTTP call is never inside a database transaction:
 * claim rows (short transaction), POST, record the outcome (short
 * transaction). A failed attempt puts the alert back to PENDING for the next
 * run until the attempts are used up.
 *
 * <p>Delivery is at-least-once: if the process dies after the POST and
 * before the outcome is recorded, the stale claim is picked up again and the
 * webhook is called a second time. The payload's {@code alertId} lets a
 * receiver drop the repeat.
 */
@Component
public class BudgetAlertSender {

	static final int BATCH_SIZE = 20;
	/** A claim older than this belongs to a sender that died mid-delivery. */
	static final long STALE_CLAIM_SECONDS = 300;

	private static final Logger log = LoggerFactory.getLogger(BudgetAlertSender.class);

	private final BudgetAlertRepository budgetAlertRepository;
	private final BudgetRepository budgetRepository;
	private final WebhookUrlValidator webhookUrlValidator;
	private final TransactionTemplate transactionTemplate;
	private final BudgetProperties properties;
	private final RestClient restClient;

	public BudgetAlertSender(BudgetAlertRepository budgetAlertRepository, BudgetRepository budgetRepository,
			WebhookUrlValidator webhookUrlValidator, TransactionTemplate transactionTemplate,
			BudgetProperties properties) {
		this.budgetAlertRepository = budgetAlertRepository;
		this.budgetRepository = budgetRepository;
		this.webhookUrlValidator = webhookUrlValidator;
		this.transactionTemplate = transactionTemplate;
		this.properties = properties;
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
				.connectTimeout(properties.webhookConnectTimeout())
				// A redirect could lead to an address the URL check never saw
				.followRedirects(HttpClient.Redirect.NEVER)
				.build());
		requestFactory.setReadTimeout(properties.webhookReadTimeout());
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** Body POSTed to the webhook. */
	public record AlertPayload(UUID alertId, UUID budgetId, BudgetScope scopeType, UUID scopeId,
			LocalDate periodStart, int thresholdPct, BigDecimal spendUsd, BigDecimal limitUsd) {
	}

	/** @return how many alerts were delivered in this run */
	public int sendPending() {
		List<UUID> claimed = transactionTemplate.execute(status ->
				budgetAlertRepository.claimUndelivered(BATCH_SIZE, STALE_CLAIM_SECONDS));
		int sent = 0;
		for (UUID alertId : claimed) {
			if (deliver(alertId)) {
				sent++;
			}
		}
		return sent;
	}

	private boolean deliver(UUID alertId) {
		BudgetAlert alert = budgetAlertRepository.findById(alertId).orElse(null);
		Budget budget = alert == null ? null : budgetRepository.findById(alert.getBudgetId()).orElse(null);
		if (budget == null) {
			return false; // budget deleted since the claim; its alerts went with it
		}
		boolean delivered = post(budget, alert);
		transactionTemplate.executeWithoutResult(status ->
				budgetAlertRepository.findById(alertId).ifPresent(current -> {
					if (delivered) {
						current.markSent();
					} else {
						current.markAttemptFailed(properties.maxDeliveryAttempts());
					}
				}));
		return delivered;
	}

	private boolean post(Budget budget, BudgetAlert alert) {
		try {
			webhookUrlValidator.validate(budget.getWebhookUrl());
			restClient.post().uri(budget.getWebhookUrl())
					.contentType(MediaType.APPLICATION_JSON)
					.body(new AlertPayload(alert.getId(), budget.getId(), budget.getScopeType(),
							budget.getScopeId(), alert.getPeriodStart(), alert.getThresholdPct(),
							alert.getSpendUsd(), alert.getLimitUsd()))
					.retrieve()
					.toBodilessEntity();
			return true;
		} catch (Exception e) {
			log.warn("Budget alert {} not delivered (attempt {}): {}",
					alert.getId(), alert.getAttempts() + 1, e.toString());
			return false;
		}
	}

}
