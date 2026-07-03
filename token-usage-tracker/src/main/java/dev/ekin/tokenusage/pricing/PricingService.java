package dev.ekin.tokenusage.pricing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
public class PricingService {

	/** Matches NUMERIC(12,6) of usage_event.cost_usd. */
	static final int COST_SCALE = 6;

	private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

	private final ModelPricingRepository modelPricingRepository;

	public PricingService(ModelPricingRepository modelPricingRepository) {
		this.modelPricingRepository = modelPricingRepository;
	}

	@Transactional(readOnly = true)
	public ModelPricing findActivePricing(String model, Instant at) {
		List<ModelPricing> candidates = modelPricingRepository.findActiveAt(model, at);
		if (candidates.isEmpty()) {
			throw new PricingNotFoundException(model, at);
		}
		return candidates.getFirst();
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