package dev.ekin.tokenusage.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param webhookConnectTimeout    limit for opening the connection to a webhook.
 * @param webhookReadTimeout       limit for the webhook's whole response.
 * @param maxDeliveryAttempts      attempts before an alert is marked FAILED.
 * @param allowInsecureWebhookUrls accept plain-http and private-network
 *                                 webhook URLs. For local development and
 *                                 tests only: with it on, an admin can make
 *                                 the server call hosts inside its network.
 */
@ConfigurationProperties(prefix = "app.budgets")
public record BudgetProperties(Duration webhookConnectTimeout, Duration webhookReadTimeout,
		Integer maxDeliveryAttempts, boolean allowInsecureWebhookUrls) {

	public BudgetProperties {
		webhookConnectTimeout = webhookConnectTimeout != null ? webhookConnectTimeout : Duration.ofSeconds(2);
		webhookReadTimeout = webhookReadTimeout != null ? webhookReadTimeout : Duration.ofSeconds(5);
		maxDeliveryAttempts = maxDeliveryAttempts != null ? maxDeliveryAttempts : 5;
	}

}
