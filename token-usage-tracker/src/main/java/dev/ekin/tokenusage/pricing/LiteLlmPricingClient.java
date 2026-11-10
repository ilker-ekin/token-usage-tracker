package dev.ekin.tokenusage.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads current model prices from LiteLLM's community-maintained pricing
 * catalog and converts its per-token costs to this schema's per-1k prices.
 * See <a href="https://github.com/BerriAI/litellm">BerriAI/litellm</a>.
 *
 * <p>The catalog is one large JSON file, so it is downloaded with strict
 * timeouts and kept in memory for {@link LiteLlmProperties#cacheTtl()}: a
 * burst of requests for unknown or mistyped models costs one download, not
 * one each. After a failed download no new attempt is made for
 * {@link LiteLlmProperties#failureBackoff()}, so an outage does not make
 * every such request wait for the timeout.
 */
@Component
public class LiteLlmPricingClient {

	private static final Logger log = LoggerFactory.getLogger(LiteLlmPricingClient.class);
	private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

	private final LiteLlmProperties properties;
	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private JsonNode catalog;
	private Instant catalogFetchedAt;
	private Instant lastFailureAt;

	public LiteLlmPricingClient(LiteLlmProperties properties) {
		this.properties = properties;
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** Cache prices are null when the catalog does not list them for the model. */
	public record ModelPrice(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k,
			BigDecimal cacheReadPricePer1k, BigDecimal cacheCreationPricePer1k) {

		public ModelPrice(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {
			this(model, inputPricePer1k, outputPricePer1k, null, null);
		}
	}

	/**
	 * Makes a network call on a cache miss: never call this with a database
	 * transaction open.
	 *
	 * @return the model's current price, or empty when the catalog does not
	 *         list the model.
	 * @throws PricingCatalogUnavailableException when the catalog cannot be
	 *         read and no earlier copy is held.
	 */
	public Optional<ModelPrice> fetchPrice(String model) {
		JsonNode entry = catalog().get(model);
		if (entry == null || !entry.hasNonNull("input_cost_per_token")
				|| !entry.hasNonNull("output_cost_per_token")) {
			return Optional.empty();
		}
		return Optional.of(new ModelPrice(model,
				perTokenToPer1k(entry.get("input_cost_per_token").decimalValue()),
				perTokenToPer1k(entry.get("output_cost_per_token").decimalValue()),
				optionalPer1k(entry, "cache_read_input_token_cost"),
				optionalPer1k(entry, "cache_creation_input_token_cost")));
	}

	/** Synchronized so concurrent misses share one download instead of racing. */
	private synchronized JsonNode catalog() {
		Instant now = Instant.now();
		if (catalog != null && now.isBefore(catalogFetchedAt.plus(properties.cacheTtl()))) {
			return catalog;
		}
		if (lastFailureAt != null && now.isBefore(lastFailureAt.plus(properties.failureBackoff()))) {
			return staleCatalogOrThrow(null);
		}
		try {
			String body = restClient.get().uri(properties.url()).retrieve().body(String.class);
			JsonNode fresh = objectMapper.readTree(body);
			if (fresh == null || !fresh.isObject()) {
				throw new IllegalStateException("catalog is not a JSON object");
			}
			catalog = fresh;
			catalogFetchedAt = now;
			lastFailureAt = null;
			return catalog;
		} catch (Exception e) {
			lastFailureAt = now;
			log.warn("Could not fetch the LiteLLM pricing catalog: {}", e.toString());
			return staleCatalogOrThrow(e);
		}
	}

	/** An expired copy still beats refusing to price a model it lists. */
	private JsonNode staleCatalogOrThrow(Exception cause) {
		if (catalog != null) {
			return catalog;
		}
		throw new PricingCatalogUnavailableException(
				"The model pricing catalog is unavailable; retry later", cause);
	}

	private static BigDecimal optionalPer1k(JsonNode entry, String field) {
		return entry.hasNonNull(field) ? perTokenToPer1k(entry.get(field).decimalValue()) : null;
	}

	private static BigDecimal perTokenToPer1k(BigDecimal perToken) {
		return perToken.multiply(ONE_THOUSAND).stripTrailingZeros();
	}

}
