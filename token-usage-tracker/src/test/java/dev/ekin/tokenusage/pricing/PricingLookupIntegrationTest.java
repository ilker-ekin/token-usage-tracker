package dev.ekin.tokenusage.pricing;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.analytics.Granularity;
import dev.ekin.tokenusage.analytics.RollupRepository;
import dev.ekin.tokenusage.pricing.LiteLlmPricingClient.ModelPrice;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.TeamRepository;
import dev.ekin.tokenusage.usage.UsageEvent;
import dev.ekin.tokenusage.usage.UsageEventRepository;
import dev.ekin.tokenusage.usage.UsageService;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ingest of a model the tracker has no price for: the catalog lookup must
 * run with no database transaction open, and the price it returns must be
 * stored exactly once however many requests miss at the same time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PricingLookupIntegrationTest {

	private static final Instant EVENT_TIME = Instant.parse("2026-06-15T10:30:00Z");
	private static final ModelPrice NEW_MODEL_PRICE = new ModelPrice("brand-new-model",
			new BigDecimal("0.003"), new BigDecimal("0.015"),
			new BigDecimal("0.0003"), new BigDecimal("0.00375"));

	@Autowired
	UsageService usageService;
	@Autowired
	StubLiteLlmPricingClient catalog;
	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	TeamRepository teamRepository;
	@Autowired
	MemberRepository memberRepository;
	@Autowired
	ProjectRepository projectRepository;
	@Autowired
	ModelPricingRepository modelPricingRepository;
	@Autowired
	UsageEventRepository usageEventRepository;
	@Autowired
	RollupRepository rollupRepository;

	Member member;
	Project project;

	@BeforeEach
	void setUp() {
		rollupRepository.deleteAll();
		usageEventRepository.deleteAll();
		modelPricingRepository.deleteAll();
		memberRepository.deleteAll();
		projectRepository.deleteAll();
		teamRepository.deleteAll();
		organizationRepository.deleteAll();

		Organization org = organizationRepository.save(new Organization("Lookup Org", "lookup-org"));
		member = memberRepository.save(new Member(org.getId(), null,
				"lookup@lookup.dev", "Lookup", null, "MEMBER"));
		project = projectRepository.save(new Project(org.getId(), "lookup-project"));
	}

	@AfterEach
	void resetCatalog() {
		catalog.reset();
	}

	private IngestCommand command(String model) {
		return new IngestCommand(project.getId(), model, 1000, 2000, 1, 1, EVENT_TIME);
	}

	@Test
	void catalogIsCalledWithNoTransactionOpenAndItsPriceIsStored() {
		AtomicInteger calls = new AtomicInteger();
		AtomicBoolean transactionOpenDuringCall = new AtomicBoolean();
		catalog.respondWith(model -> {
			calls.incrementAndGet();
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				transactionOpenDuringCall.set(true);
			}
			return Optional.of(NEW_MODEL_PRICE);
		});

		UsageEvent event = usageService.ingest(member.getId(), command("brand-new-model"));

		assertThat(calls).hasValue(1);
		assertThat(transactionOpenDuringCall).isFalse();
		assertThat(event.getCostUsd()).isEqualByComparingTo("0.033");
		assertThat(modelPricingRepository.findAll()).hasSize(1).first()
				.satisfies(p -> assertThat(p.getCacheReadPricePer1k()).isEqualByComparingTo("0.0003"));
		// The stored row serves the next event; the catalog is not asked again
		usageService.ingest(member.getId(), command("brand-new-model"));
		assertThat(calls).hasValue(1);
	}

	@Test
	void datedSnapshotFallsBackToTheAliasInTheCatalog() {
		catalog.respondWith(model -> model.equals("brand-new-model")
				? Optional.of(NEW_MODEL_PRICE) : Optional.empty());

		UsageEvent event = usageService.ingest(member.getId(), command("brand-new-model-20260101"));

		assertThat(event.getModel()).isEqualTo("brand-new-model-20260101");
		assertThat(modelPricingRepository.findAll()).hasSize(1).first()
				.satisfies(p -> assertThat(p.getModel()).isEqualTo("brand-new-model"));
	}

	@Test
	void unavailableCatalogBooksNothing() {
		catalog.respondWith(model -> {
			throw new PricingCatalogUnavailableException("down", null);
		});

		assertThatThrownBy(() -> usageService.ingest(member.getId(), command("brand-new-model")))
				.isInstanceOf(PricingCatalogUnavailableException.class);
		assertThat(usageEventRepository.count()).isZero();
		assertThat(rollupRepository.countByGranularity(Granularity.DAY)).isZero();
	}

	@Test
	void knownModelNeverTouchesTheCatalog() {
		modelPricingRepository.save(new ModelPricing("claude-sonnet-4-6",
				new BigDecimal("0.003"), new BigDecimal("0.015"),
				Instant.parse("2026-01-01T00:00:00Z"), null));
		catalog.respondWith(model -> {
			throw new AssertionError("catalog must not be called for a priced model");
		});

		usageService.ingest(member.getId(), command("claude-sonnet-4-6"));

		assertThat(usageEventRepository.count()).isEqualTo(1);
	}

	@Test
	void parallelFirstUsesOfANewModelStoreItsPriceOnce() throws Exception {
		catalog.respondWith(model -> Optional.of(NEW_MODEL_PRICE));
		int requests = 30;
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				pool.submit(() -> {
					try {
						start.await();
						usageService.ingest(member.getId(), command("brand-new-model"));
					} catch (Throwable t) {
						failures.add(t);
					}
				});
			}
			start.countDown();
		}

		assertThat(failures).as("%s", failures.stream().map(Throwable::toString).distinct().toList())
				.isEmpty();
		assertThat(usageEventRepository.count()).isEqualTo(requests);
		assertThat(modelPricingRepository.count()).isEqualTo(1);
		assertThat(rollupRepository.findByGranularity(Granularity.DAY).getFirst().getTotalInputTokens())
				.isEqualTo(1000L * requests);
	}

}
