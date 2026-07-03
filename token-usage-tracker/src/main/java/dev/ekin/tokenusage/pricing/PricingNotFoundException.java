package dev.ekin.tokenusage.pricing;

import java.time.Instant;

public class PricingNotFoundException extends RuntimeException {

	public PricingNotFoundException(String model, Instant at) {
		super("No pricing configured for model '" + model + "' at " + at);
	}

}