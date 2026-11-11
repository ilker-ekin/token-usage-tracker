package dev.ekin.tokenusage.pricing;

import dev.ekin.tokenusage.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_pricing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelPricing extends BaseEntity {

	/**
	 * The org this price applies to, or null for a global catalog row. Only
	 * global rows are shared between tenants, and the API never writes one.
	 */
	@Column(name = "org_id")
	private UUID orgId;

	@Column(nullable = false)
	private String model;

	@Column(name = "input_price_per_1k", nullable = false)
	private BigDecimal inputPricePer1k;

	@Column(name = "output_price_per_1k", nullable = false)
	private BigDecimal outputPricePer1k;

	/** Null when no cache-read price is known; cost then uses the input price. */
	@Column(name = "cache_read_price_per_1k")
	private BigDecimal cacheReadPricePer1k;

	/** Null when no cache-write price is known; cost then uses the input price. */
	@Column(name = "cache_creation_price_per_1k")
	private BigDecimal cacheCreationPricePer1k;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	@Column(name = "valid_to")
	private Instant validTo;

	/** Pricing without cache prices: cache tokens are charged at the input price. */
	public ModelPricing(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k,
			Instant validFrom, Instant validTo) {
		this(model, inputPricePer1k, outputPricePer1k, null, null, validFrom, validTo);
	}

	public ModelPricing(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k,
			BigDecimal cacheReadPricePer1k, BigDecimal cacheCreationPricePer1k,
			Instant validFrom, Instant validTo) {
		this.model = model;
		this.inputPricePer1k = inputPricePer1k;
		this.outputPricePer1k = outputPricePer1k;
		this.cacheReadPricePer1k = cacheReadPricePer1k;
		this.cacheCreationPricePer1k = cacheCreationPricePer1k;
		this.validFrom = validFrom;
		this.validTo = validTo;
	}

	/** A price that only {@code orgId}'s usage is charged at; it wins over the global row. */
	public static ModelPricing orgOverride(UUID orgId, String model, BigDecimal inputPricePer1k,
			BigDecimal outputPricePer1k, BigDecimal cacheReadPricePer1k, BigDecimal cacheCreationPricePer1k,
			Instant validFrom, Instant validTo) {
		ModelPricing p = new ModelPricing(model, inputPricePer1k, outputPricePer1k,
				cacheReadPricePer1k, cacheCreationPricePer1k, validFrom, validTo);
		p.orgId = orgId;
		return p;
	}

	public boolean isGlobal() {
		return orgId == null;
	}

}
