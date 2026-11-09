package dev.ekin.tokenusage.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounds on the event time a client may report.
 *
 * @param maxFuture   how far ahead of the server clock an event may be dated;
 *                    absorbs clock skew, nothing more.
 * @param maxEventAge how old an event may be. Rollups for older periods are
 *                    never revisited by the nightly recompute, so an
 *                    arbitrarily old date would create rows nobody checks.
 */
@ConfigurationProperties(prefix = "app.ingest")
public record IngestProperties(Duration maxFuture, Duration maxEventAge) {

	public IngestProperties {
		maxFuture = maxFuture != null ? maxFuture : Duration.ofMinutes(5);
		maxEventAge = maxEventAge != null ? maxEventAge : Duration.ofDays(90);
	}

}
