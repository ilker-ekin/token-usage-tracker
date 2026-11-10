package dev.ekin.tokenusage.pricing;

import java.util.Optional;
import java.util.function.Function;

/**
 * Stands in for the real catalog client in every Spring test, so the suite
 * never depends on the network. Lists no model unless a test says otherwise.
 */
public class StubLiteLlmPricingClient extends LiteLlmPricingClient {

	private volatile Function<String, Optional<ModelPrice>> behavior = model -> Optional.empty();

	public StubLiteLlmPricingClient() {
		super(LiteLlmProperties.defaults());
	}

	public void respondWith(Function<String, Optional<ModelPrice>> behavior) {
		this.behavior = behavior;
	}

	public void reset() {
		this.behavior = model -> Optional.empty();
	}

	@Override
	public Optional<ModelPrice> fetchPrice(String model) {
		return behavior.apply(model);
	}

}
