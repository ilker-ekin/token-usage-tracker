package dev.ekin.tokenusage.pricing;

/**
 * The external pricing catalog could not be read in time. Unlike
 * {@link PricingNotFoundException} this says nothing about the model: the
 * same request may succeed when retried.
 */
public class PricingCatalogUnavailableException extends RuntimeException {

	public PricingCatalogUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
