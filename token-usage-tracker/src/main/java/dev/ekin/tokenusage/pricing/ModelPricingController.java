package dev.ekin.tokenusage.pricing;

import dev.ekin.tokenusage.auth.MemberPrincipal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

/**
 * Model prices as one org sees them. A price posted here is an override for
 * the caller's org only: it changes what that org's usage costs and nobody
 * else's. Global catalog prices are never written through the API.
 */
@RestController
@RequestMapping("/model-pricing")
public class ModelPricingController {

	/** GLOBAL rows apply to every org without an override; ORG rows only to the caller's. */
	public enum Scope { GLOBAL, ORG }

	private final ModelPricingRepository modelPricingRepository;

	public ModelPricingController(ModelPricingRepository modelPricingRepository) {
		this.modelPricingRepository = modelPricingRepository;
	}

	public record CreatePricingRequest(
			@NotBlank String model,
			@NotNull @DecimalMin("0") BigDecimal inputPricePer1k,
			@NotNull @DecimalMin("0") BigDecimal outputPricePer1k,
			@DecimalMin("0") BigDecimal cacheReadPricePer1k,
			@DecimalMin("0") BigDecimal cacheCreationPricePer1k,
			@NotNull Instant validFrom,
			Instant validTo) {
	}

	public record PricingResponse(UUID id, Scope scope, String model, BigDecimal inputPricePer1k,
			BigDecimal outputPricePer1k, BigDecimal cacheReadPricePer1k,
			BigDecimal cacheCreationPricePer1k, Instant validFrom, Instant validTo) {

		static PricingResponse from(ModelPricing p) {
			return new PricingResponse(p.getId(), p.isGlobal() ? Scope.GLOBAL : Scope.ORG, p.getModel(),
					p.getInputPricePer1k(), p.getOutputPricePer1k(), p.getCacheReadPricePer1k(),
					p.getCacheCreationPricePer1k(), p.getValidFrom(), p.getValidTo());
		}
	}

	/** Adds a price override for the caller's org. */
	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.CREATED)
	public PricingResponse create(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestBody @Validated CreatePricingRequest request) {
		ModelPricing pricing = ModelPricing.orgOverride(principal.orgId(), request.model(),
				request.inputPricePer1k(), request.outputPricePer1k(), request.cacheReadPricePer1k(),
				request.cacheCreationPricePer1k(), request.validFrom(), request.validTo());
		return PricingResponse.from(modelPricingRepository.save(pricing));
	}

	/** The global prices and the caller's org overrides; another org's overrides are never listed. */
	@GetMapping
	public List<PricingResponse> list(@AuthenticationPrincipal MemberPrincipal principal) {
		return modelPricingRepository.findVisibleTo(principal.orgId()).stream()
				.map(PricingResponse::from)
				.toList();
	}

}
