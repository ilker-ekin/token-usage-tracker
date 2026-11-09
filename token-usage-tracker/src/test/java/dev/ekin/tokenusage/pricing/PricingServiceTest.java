package dev.ekin.tokenusage.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PricingServiceTest {

	private final PricingService pricingService = new PricingService(null, null, null);

	private static ModelPricing pricing(String inputPer1k, String outputPer1k) {
		return new ModelPricing("claude-sonnet-4-6",
				new BigDecimal(inputPer1k), new BigDecimal(outputPer1k),
				Instant.parse("2026-01-01T00:00:00Z"), null);
	}

	private static ModelPricing pricing(String inputPer1k, String outputPer1k,
			String cacheReadPer1k, String cacheCreationPer1k) {
		return new ModelPricing("claude-sonnet-4-6",
				new BigDecimal(inputPer1k), new BigDecimal(outputPer1k),
				new BigDecimal(cacheReadPer1k), new BigDecimal(cacheCreationPer1k),
				Instant.parse("2026-01-01T00:00:00Z"), null);
	}

	@Test
	void cacheTokensUseTheirOwnPrices() {
		// 1000 in @ 0.003 + 2000 out @ 0.015 + 10000 read @ 0.0003 + 4000 write @ 0.00375
		// = 0.003 + 0.030 + 0.003 + 0.015
		BigDecimal cost = pricingService.computeCost(
				pricing("0.003", "0.015", "0.0003", "0.00375"), 1000, 2000, 10_000, 4000);
		assertThat(cost).isEqualByComparingTo("0.051");
	}

	@Test
	void cacheTokensFallBackToInputPriceWhenNoCachePriceIsKnown() {
		// 1000 in + 10000 read + 4000 write, all @ 0.003 = 0.045; 2000 out @ 0.015 = 0.030
		BigDecimal cost = pricingService.computeCost(pricing("0.003", "0.015"), 1000, 2000, 10_000, 4000);
		assertThat(cost).isEqualByComparingTo("0.075");
	}

	@Test
	void termsAreSummedBeforeRoundingNotRoundedOneByOne() {
		// One read and one write token @ 0.0003/1k are 0.0000003 each. Rounded
		// separately both vanish; summed first they are 0.0000006 → 0.000001.
		BigDecimal cost = pricingService.computeCost(
				pricing("0.003", "0.015", "0.0003", "0.0003"), 0, 0, 1, 1);
		assertThat(cost).isEqualByComparingTo("0.000001");
		assertThat(cost.scale()).isEqualTo(6);
	}

	@Test
	void sevenDecimalCachePriceIsNotTruncated() {
		// 1,000,000 read tokens @ 0.0000375/1k = 0.0375 (0.038 if the price lost its 7th decimal)
		BigDecimal cost = pricingService.computeCost(
				pricing("0.00015", "0.0006", "0.0000375", "0.00015"), 0, 0, 1_000_000, 0);
		assertThat(cost).isEqualByComparingTo("0.0375");
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
