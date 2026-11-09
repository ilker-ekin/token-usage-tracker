package dev.ekin.tokenusage.pricing;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reads current model prices from LiteLLM's community-maintained pricing
 * catalog and converts its per-token costs to this schema's per-1k prices.
 * See <a href="https://github.com/BerriAI/litellm">BerriAI/litellm</a>.
 */
@Component
public class LiteLlmPricingClient {

	static final String PRICING_URL =
			"https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json";

	private static final Logger log = LoggerFactory.getLogger(LiteLlmPricingClient.class);
	private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

	private final RestClient restClient = RestClient.create();

	/** Cache prices are null when the catalog does not list them for the model. */
	public record ModelPrice(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k,
			BigDecimal cacheReadPricePer1k, BigDecimal cacheCreationPricePer1k) {

		public ModelPrice(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {
			this(model, inputPricePer1k, outputPricePer1k, null, null);
		}
	}

	/**
	 * @return the model's current price, or empty when the catalog is
	 *         unreachable or does not list the model.
	 */
	public Optional<ModelPrice> fetchPrice(String model) {
		try {
			JsonNode catalog = restClient.get().uri(PRICING_URL).retrieve().body(JsonNode.class);
			JsonNode entry = catalog == null ? null : catalog.get(model);
			if (entry == null || !entry.hasNonNull("input_cost_per_token")
					|| !entry.hasNonNull("output_cost_per_token")) {
				return Optional.empty();
			}
			return Optional.of(new ModelPrice(model,
					perTokenToPer1k(entry.get("input_cost_per_token").decimalValue()),
					perTokenToPer1k(entry.get("output_cost_per_token").decimalValue()),
					optionalPer1k(entry, "cache_read_input_token_cost"),
					optionalPer1k(entry, "cache_creation_input_token_cost")));
		} catch (Exception e) {
			log.warn("Could not fetch LiteLLM pricing for {}: {}", model, e.getMessage());
			return Optional.empty();
		}
	}

	private static BigDecimal optionalPer1k(JsonNode entry, String field) {
		return entry.hasNonNull(field) ? perTokenToPer1k(entry.get(field).decimalValue()) : null;
	}

	private static BigDecimal perTokenToPer1k(BigDecimal perToken) {
		return perToken.multiply(ONE_THOUSAND).stripTrailingZeros();
	}

}