package dev.ekin.tokenusage.pricing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/model-pricing")
public class ModelPricingController {

	private final ModelPricingRepository modelPricingRepository;

	public ModelPricingController(ModelPricingRepository modelPricingRepository) {
		this.modelPricingRepository = modelPricingRepository;
	}

	public record CreatePricingRequest(
			@NotBlank String model,
			@NotNull @DecimalMin("0") BigDecimal inputPricePer1k,
			@NotNull @DecimalMin("0") BigDecimal outputPricePer1k,
			@NotNull Instant validFrom,
			Instant validTo) {
	}

	public record PricingResponse(UUID id, String model, BigDecimal inputPricePer1k,
			BigDecimal outputPricePer1k, Instant validFrom, Instant validTo) {

		static PricingResponse from(ModelPricing p) {
			return new PricingResponse(p.getId(), p.getModel(), p.getInputPricePer1k(),
					p.getOutputPricePer1k(), p.getValidFrom(), p.getValidTo());
		}
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.CREATED)
	public PricingResponse create(@RequestBody @Validated CreatePricingRequest request) {
		ModelPricing pricing = new ModelPricing(request.model(), request.inputPricePer1k(),
				request.outputPricePer1k(), request.validFrom(), request.validTo());
		return PricingResponse.from(modelPricingRepository.save(pricing));
	}

	@GetMapping
	public List<PricingResponse> list() {
		return modelPricingRepository.findAll().stream()
				.map(PricingResponse::from)
				.toList();
	}

}