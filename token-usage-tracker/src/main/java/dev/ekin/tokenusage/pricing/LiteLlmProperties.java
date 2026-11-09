package dev.ekin.tokenusage.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param url            location of LiteLLM's pricing catalog JSON.
 * @param connectTimeout limit for opening the connection.
 * @param readTimeout    limit for the whole response, headers and body.
 * @param cacheTtl       how long a downloaded catalog is reused.
 * @param failureBackoff how long after a failed download no new attempt is made.
 */
@ConfigurationProperties(prefix = "app.pricing.litellm")
public record LiteLlmProperties(String url, Duration connectTimeout, Duration readTimeout,
		Duration cacheTtl, Duration failureBackoff) {

	static final String DEFAULT_URL =
			"https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json";

	public LiteLlmProperties {
		url = url != null ? url : DEFAULT_URL;
		connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
		readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(5);
		cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofMinutes(10);
		failureBackoff = failureBackoff != null ? failureBackoff : Duration.ofSeconds(30);
	}

	public static LiteLlmProperties defaults() {
		return new LiteLlmProperties(null, null, null, null, null);
	}

}
