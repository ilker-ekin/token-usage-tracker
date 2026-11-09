package dev.ekin.tokenusage.pricing;

import com.sun.net.httpserver.HttpServer;
import dev.ekin.tokenusage.pricing.LiteLlmPricingClient.ModelPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real client against a local HTTP server standing in for the catalog. */
class LiteLlmPricingClientTest {

	private static final String CATALOG = """
			{
			  "sample_spec": {"note": "not a model"},
			  "claude-sonnet-4-6": {
			    "input_cost_per_token": 0.000003,
			    "output_cost_per_token": 0.000015,
			    "cache_read_input_token_cost": 0.0000003,
			    "cache_creation_input_token_cost": 0.00000375
			  },
			  "no-cache-model": {
			    "input_cost_per_token": 0.000001,
			    "output_cost_per_token": 0.000002
			  }
			}
			""";

	private final AtomicInteger requests = new AtomicInteger();
	private volatile long delayMillis;
	private volatile int status = 200;
	private HttpServer server;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog.json", exchange -> {
			requests.incrementAndGet();
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] body = CATALOG.getBytes(StandardCharsets.UTF_8);
			// GitHub serves the raw file as text/plain, not application/json
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private LiteLlmPricingClient client(Duration readTimeout, Duration cacheTtl, Duration failureBackoff) {
		return new LiteLlmPricingClient(new LiteLlmProperties(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/catalog.json",
				Duration.ofSeconds(1), readTimeout, cacheTtl, failureBackoff));
	}

	private LiteLlmPricingClient client() {
		return client(Duration.ofSeconds(2), Duration.ofMinutes(10), Duration.ofSeconds(30));
	}

	@Test
	void convertsPerTokenCostsToPer1kIncludingCachePrices() {
		ModelPrice price = client().fetchPrice("claude-sonnet-4-6").orElseThrow();

		assertThat(price.inputPricePer1k()).isEqualByComparingTo("0.003");
		assertThat(price.outputPricePer1k()).isEqualByComparingTo("0.015");
		assertThat(price.cacheReadPricePer1k()).isEqualByComparingTo("0.0003");
		assertThat(price.cacheCreationPricePer1k()).isEqualByComparingTo("0.00375");
	}

	@Test
	void cachePricesAreNullWhenTheCatalogHasNone() {
		ModelPrice price = client().fetchPrice("no-cache-model").orElseThrow();

		assertThat(price.cacheReadPricePer1k()).isNull();
		assertThat(price.cacheCreationPricePer1k()).isNull();
	}

	@Test
	void unknownModelAndNonModelEntriesAreEmpty() {
		LiteLlmPricingClient client = client();

		assertThat(client.fetchPrice("no-such-model")).isEmpty();
		assertThat(client.fetchPrice("sample_spec")).isEmpty();
	}

	@Test
	void catalogIsDownloadedOnceWithinTheCacheWindow() {
		LiteLlmPricingClient client = client();

		client.fetchPrice("claude-sonnet-4-6");
		client.fetchPrice("typo-1");
		client.fetchPrice("typo-2");

		assertThat(requests).hasValue(1);
	}

	@Test
	void catalogIsDownloadedAgainAfterTheCacheWindow() {
		LiteLlmPricingClient client = client(Duration.ofSeconds(2), Duration.ZERO, Duration.ofSeconds(30));

		client.fetchPrice("claude-sonnet-4-6");
		client.fetchPrice("claude-sonnet-4-6");

		assertThat(requests).hasValue(2);
	}

	@Test
	void slowCatalogFailsWithinTheReadTimeoutInsteadOfHanging() {
		delayMillis = 3000;
		LiteLlmPricingClient client = client(Duration.ofMillis(300), Duration.ofMinutes(10), Duration.ofSeconds(30));

		long started = System.nanoTime();
		assertThatThrownBy(() -> client.fetchPrice("claude-sonnet-4-6"))
				.isInstanceOf(PricingCatalogUnavailableException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2000));
	}

	@Test
	void afterAFailureNoNewDownloadIsAttemptedDuringTheBackoff() {
		status = 500;
		LiteLlmPricingClient client = client();

		assertThatThrownBy(() -> client.fetchPrice("claude-sonnet-4-6"))
				.isInstanceOf(PricingCatalogUnavailableException.class);
		assertThatThrownBy(() -> client.fetchPrice("claude-sonnet-4-6"))
				.isInstanceOf(PricingCatalogUnavailableException.class);

		assertThat(requests).hasValue(1);
	}

	@Test
	void anExpiredCatalogIsStillServedWhenTheRefreshFails() {
		LiteLlmPricingClient client = client(Duration.ofSeconds(2), Duration.ZERO, Duration.ofSeconds(30));
		client.fetchPrice("claude-sonnet-4-6");
		status = 500;

		Optional<ModelPrice> price = client.fetchPrice("claude-sonnet-4-6");

		assertThat(price).isPresent();
	}

}
