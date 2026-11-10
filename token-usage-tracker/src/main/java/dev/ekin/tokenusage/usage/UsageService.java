package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.analytics.AnalyticsCacheService;
import dev.ekin.tokenusage.analytics.RollupService;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.PricingService;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.ProjectNotFoundException;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class UsageService {

	private final UsageEventRepository usageEventRepository;
	private final MemberRepository memberRepository;
	private final ProjectRepository projectRepository;
	private final PricingService pricingService;
	private final RollupService rollupService;
	private final AnalyticsCacheService analyticsCacheService;
	private final TransactionTemplate transactionTemplate;
	private final IngestProperties ingestProperties;

	public UsageService(UsageEventRepository usageEventRepository,
			MemberRepository memberRepository, ProjectRepository projectRepository,
			PricingService pricingService,
			RollupService rollupService, AnalyticsCacheService analyticsCacheService,
			TransactionTemplate transactionTemplate, IngestProperties ingestProperties) {
		this.transactionTemplate = transactionTemplate;
		this.ingestProperties = ingestProperties;
		this.usageEventRepository = usageEventRepository;
		this.memberRepository = memberRepository;
		this.projectRepository = projectRepository;
		this.pricingService = pricingService;
		this.rollupService = rollupService;
		this.analyticsCacheService = analyticsCacheService;
	}

	/**
	 * {@code inputTokens} is uncached input only. Cache reads and cache writes
	 * are reported separately because providers bill them at their own rates.
	 *
	 * <p>The constructor rejects values no event can have, so callers that do
	 * not come through the HTTP layer (seeder, jobs, tests) cannot book them
	 * either.
	 */
	public record IngestCommand(UUID projectId, String model, int inputTokens, int outputTokens,
			int cacheReadTokens, int cacheCreationTokens,
			Integer linesAdded, Integer linesRemoved, Instant eventTime) {

		public IngestCommand {
			if (projectId == null || model == null || model.isBlank() || eventTime == null) {
				throw new InvalidUsageEventException("projectId, model and eventTime are required");
			}
			requireNotNegative("inputTokens", inputTokens);
			requireNotNegative("outputTokens", outputTokens);
			requireNotNegative("cacheReadTokens", cacheReadTokens);
			requireNotNegative("cacheCreationTokens", cacheCreationTokens);
			requireNotNegative("linesAdded", linesAdded == null ? 0 : linesAdded);
			requireNotNegative("linesRemoved", linesRemoved == null ? 0 : linesRemoved);
		}

		/** An event without prompt caching. */
		public IngestCommand(UUID projectId, String model, int inputTokens, int outputTokens,
				Integer linesAdded, Integer linesRemoved, Instant eventTime) {
			this(projectId, model, inputTokens, outputTokens, 0, 0, linesAdded, linesRemoved, eventTime);
		}

		private static void requireNotNegative(String field, int value) {
			if (value < 0) {
				throw new InvalidUsageEventException(field + " must not be negative");
			}
		}
	}

	/**
	 * @param created false when the idempotency key had already been used in
	 *                the org and {@code event} is the one booked back then
	 */
	public record IngestResult(UsageEvent event, boolean created) {
	}

	/** Ingests an event without an idempotency key: every call books a new event. */
	public UsageEvent ingest(UUID memberId, IngestCommand command) {
		return ingest(memberId, command, null).event();
	}

	/**
	 * Books the event with its cost snapshotted at ingest time. The event and
	 * all three rollups commit in one transaction, and cached analytics for
	 * the org are evicted so the dashboard reflects the write within one
	 * request. The project must belong to the member's org; a foreign project
	 * is reported exactly like a missing one.
	 *
	 * <p>This method itself is not transactional. Pricing is resolved first,
	 * because for an unknown model that means a call to an external catalog,
	 * and no database transaction may be open while it runs. Only the writes
	 * that follow share a transaction.
	 *
	 * <p>With an {@code idempotencyKey}, a key the org has already used
	 * returns the event booked the first time and changes nothing: no new
	 * event, no rollup increment, no cache eviction. The command of a replay
	 * is not compared with the original.
	 */
	public IngestResult ingest(UUID memberId, IngestCommand command, String idempotencyKey) {
		Member member = memberRepository.findById(memberId)
				.orElseThrow(() -> new NoSuchElementException("Member not found: " + memberId));
		projectRepository.findByIdAndOrgId(command.projectId(), member.getOrgId())
				.orElseThrow(() -> new ProjectNotFoundException(command.projectId()));

		// Checked before validation and pricing, so a replay succeeds even if
		// the event has aged out or the model's price has been removed since.
		if (idempotencyKey != null) {
			Optional<UsageEvent> existing = usageEventRepository
					.findByOrgIdAndIdempotencyKey(member.getOrgId(), idempotencyKey);
			if (existing.isPresent()) {
				return new IngestResult(existing.get(), false);
			}
		}

		requireEventTimeInWindow(command.eventTime());

		ModelPricing pricing = pricingService.findActivePricing(command.model(), command.eventTime());
		BigDecimal cost = pricingService.computeCost(pricing,
				command.inputTokens(), command.outputTokens(),
				command.cacheReadTokens(), command.cacheCreationTokens());

		return transactionTemplate.execute(status -> book(member, command, cost, idempotencyKey));
	}

	private void requireEventTimeInWindow(Instant eventTime) {
		Instant now = Instant.now();
		if (eventTime.isAfter(now.plus(ingestProperties.maxFuture()))) {
			throw new InvalidUsageEventException("eventTime is in the future");
		}
		if (eventTime.isBefore(now.minus(ingestProperties.maxEventAge()))) {
			throw new InvalidUsageEventException("eventTime is older than the accepted "
					+ ingestProperties.maxEventAge().toDays() + " days");
		}
	}

	/** Runs inside the ingest transaction: event, rollups and eviction are atomic. */
	private IngestResult book(Member member, IngestCommand command, BigDecimal cost,
			String idempotencyKey) {
		UsageEvent event;
		if (idempotencyKey == null) {
			event = usageEventRepository.save(new UsageEvent(
					member.getOrgId(), member.getTeamId(), member.getId(), command.projectId(),
					command.model(), command.inputTokens(), command.outputTokens(),
					command.cacheReadTokens(), command.cacheCreationTokens(),
					command.linesAdded(), command.linesRemoved(), cost, command.eventTime()));
		} else {
			UUID id = UUID.randomUUID();
			int inserted = usageEventRepository.insertIfKeyAbsent(id,
					member.getOrgId(), member.getTeamId(), member.getId(), command.projectId(),
					command.model(), command.inputTokens(), command.outputTokens(),
					command.cacheReadTokens(), command.cacheCreationTokens(),
					command.linesAdded(), command.linesRemoved(), cost, command.eventTime(),
					idempotencyKey);
			if (inserted == 0) {
				// A concurrent request with the same key committed between the
				// check in ingest and this insert; its event is visible now.
				return new IngestResult(usageEventRepository
						.findByOrgIdAndIdempotencyKey(member.getOrgId(), idempotencyKey)
						.orElseThrow(), false);
			}
			event = usageEventRepository.findById(id).orElseThrow();
		}

		rollupService.apply(event);
		analyticsCacheService.evictOrg(member.getOrgId());
		return new IngestResult(event, true);
	}

	@Transactional(readOnly = true)
	public Page<UsageEvent> search(UUID orgId, UUID memberId, UUID projectId,
			Instant from, Instant to, int page, int size) {
		Specification<UsageEvent> spec = (root, query, cb) -> cb.equal(root.get("orgId"), orgId);
		if (memberId != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("memberId"), memberId));
		}
		if (projectId != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("projectId"), projectId));
		}
		if (from != null) {
			spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("eventTime"), from));
		}
		if (to != null) {
			spec = spec.and((root, query, cb) -> cb.lessThan(root.get("eventTime"), to));
		}
		return usageEventRepository.findAll(spec,
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "eventTime")));
	}

}
