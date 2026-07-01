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

@Entity
@Table(name = "model_pricing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelPricing extends BaseEntity {

	@Column(nullable = false)
	private String model;

	@Column(name = "input_price_per_1k", nullable = false)
	private BigDecimal inputPricePer1k;

	@Column(name = "output_price_per_1k", nullable = false)
	private BigDecimal outputPricePer1k;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	@Column(name = "valid_to")
	private Instant validTo;

	public ModelPricing(String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k,
			Instant validFrom, Instant validTo) {
		this.model = model;
		this.inputPricePer1k = inputPricePer1k;
		this.outputPricePer1k = outputPricePer1k;
		this.validFrom = validFrom;
		this.validTo = validTo;
	}

}
