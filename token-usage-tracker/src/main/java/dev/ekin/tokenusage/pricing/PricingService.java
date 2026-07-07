package dev.ekin.tokenusage.pricing;

import dev.ekin.tokenusage.pricing.LiteLlmPricingClient.ModelPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PricingService {

	/** Matches NUMERIC(12,6) of usage_event.cost_usd. */
	static final int COST_SCALE = 6;

	private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

	/** Dated snapshot ids like claude-haiku-4-5-20251001 → alias claude-haiku-4-5. */
	private static final Pattern DATED_SUFFIX = Pattern.compile("^(.+)-(\\d{8})$");

	private static final Logger log = LoggerFactory.getLogger(PricingService.class);

	private final ModelPricingRepository modelPricingRepository;
	private final LiteLlmPricingClient liteLlmPricingClient;

	public PricingService(ModelPricingRepository modelPricingRepository,
			LiteLlmPricingClient liteLlmPricingClient) {
		this.modelPricingRepository = modelPricingRepository;
		this.liteLlmPricingClient = liteLlmPricingClient;
	}

	/**
	 * Resolves pricing for a model at the given instant. Providers report
	 * dated snapshot ids, so an exact match falls back to the undated alias,
	 * and a model with no row at all is looked up in LiteLLM's catalog and
	 * persisted — usage of a newly released model books without an admin
	 * having to add its price first.
	 */
	@Transactional
	public ModelPricing findActivePricing(String model, Instant at) {
		Optional<ModelPricing> exact = firstActive(model, at);
		if (exact.isPresent()) {
			return exact.get();
		}

		Matcher dated = DATED_SUFFIX.matcher(model);
		if (dated.matches()) {
			Optional<ModelPricing> alias = firstActive(dated.group(1), at);
			if (alias.isPresent()) {
				return alias.get();
			}
		}

		return fetchAndPersist(model)
				.or(() -> dated.matches() ? fetchAndPersist(dated.group(1)) : Optional.empty())
				.orElseThrow(() -> new PricingNotFoundException(model, at));
	}

	private Optional<ModelPricing> firstActive(String model, Instant at) {
		return modelPricingRepository.findActiveAt(model, at).stream().findFirst();
	}

	private Optional<ModelPricing> fetchAndPersist(String model) {
		Optional<ModelPrice> price = liteLlmPricingClient.fetchPrice(model);
		if (price.isEmpty()) {
			return Optional.empty();
		}
		ModelPrice p = price.get();
		log.info("Auto-created pricing for {} from LiteLLM catalog: in={}/1k out={}/1k",
				p.model(), p.inputPricePer1k(), p.outputPricePer1k());
		return Optional.of(modelPricingRepository.save(new ModelPricing(
				p.model(), p.inputPricePer1k(), p.outputPricePer1k(), Instant.EPOCH, null)));
	}

	public BigDecimal computeCost(ModelPricing pricing, int inputTokens, int outputTokens) {
		BigDecimal inputCost = pricing.getInputPricePer1k()
				.multiply(BigDecimal.valueOf(inputTokens));
		BigDecimal outputCost = pricing.getOutputPricePer1k()
				.multiply(BigDecimal.valueOf(outputTokens));
		return inputCost.add(outputCost)
				.divide(ONE_THOUSAND, COST_SCALE, RoundingMode.HALF_UP);
	}

}
