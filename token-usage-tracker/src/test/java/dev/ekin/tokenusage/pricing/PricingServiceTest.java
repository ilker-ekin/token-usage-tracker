package dev.ekin.tokenusage.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PricingServiceTest {

	private final PricingService pricingService = new PricingService(null);

	private static ModelPricing pricing(String inputPer1k, String outputPer1k) {
		return new ModelPricing("claude-sonnet-4-6",
				new BigDecimal(inputPer1k), new BigDecimal(outputPer1k),
				Instant.parse("2026-01-01T00:00:00Z"), null);
	}

	@Test
	void computesCostForWholeThousands() {
		// 1000 in @ $0.003/1k + 2000 out @ $0.015/1k = 0.003 + 0.030
		BigDecimal cost = pricingService.computeCost(pricing("0.003", "0.015"), 1000, 2000);
		assertThat(cost).isEqualByComparingTo("0.033");
	}

	@Test
	void computesCostForFractionalThousands() {
		// 1234 in @ $0.003/1k = 0.003702; 567 out @ $0.015/1k = 0.008505
		BigDecimal cost = pricingService.computeCost(pricing("0.003", "0.015"), 1234, 567);
		assertThat(cost).isEqualByComparingTo("0.012207");
	}

	@Test
	void zeroTokensCostNothing() {
		BigDecimal cost = pricingService.computeCost(pricing("0.005", "0.025"), 0, 0);
		assertThat(cost).isEqualByComparingTo("0");
	}

	@Test
	void roundsHalfUpToSixDecimals() {
		// 1 in @ $0.0000015/1k = 0.0000000015 → rounds to 0.000000
		BigDecimal cost = pricingService.computeCost(pricing("0.0000015", "0"), 1, 0);
		assertThat(cost.scale()).isEqualTo(6);
	}

}
