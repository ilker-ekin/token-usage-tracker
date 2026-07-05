package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.analytics.AnalyticsCacheService;
import dev.ekin.tokenusage.analytics.RollupService;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.PricingService;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class UsageService {

	private final UsageEventRepository usageEventRepository;
	private final MemberRepository memberRepository;
	private final PricingService pricingService;
	private final RollupService rollupService;
	private final AnalyticsCacheService analyticsCacheService;

	public UsageService(UsageEventRepository usageEventRepository,
			MemberRepository memberRepository, PricingService pricingService,
			RollupService rollupService, AnalyticsCacheService analyticsCacheService) {
		this.usageEventRepository = usageEventRepository;
		this.memberRepository = memberRepository;
		this.pricingService = pricingService;
		this.rollupService = rollupService;
		this.analyticsCacheService = analyticsCacheService;
	}

	public record IngestCommand(UUID projectId, String model, int inputTokens, int outputTokens,
			Integer linesAdded, Integer linesRemoved, Instant eventTime) {
	}

	/**
	 * Persists the event with its cost snapshotted at ingest time and updates
	 * all three rollups in the same transaction. Cached analytics for the org
	 * are evicted so the dashboard reflects the write within one request.
	 */
	@Transactional
	public UsageEvent ingest(UUID memberId, IngestCommand command) {
		Member member = memberRepository.findById(memberId)
				.orElseThrow(() -> new NoSuchElementException("Member not found: " + memberId));

		ModelPricing pricing = pricingService.findActivePricing(command.model(), command.eventTime());
		BigDecimal cost = pricingService.computeCost(pricing,
				command.inputTokens(), command.outputTokens());

		UsageEvent event = usageEventRepository.save(new UsageEvent(
				member.getOrgId(), member.getTeamId(), member.getId(), command.projectId(),
				command.model(), command.inputTokens(), command.outputTokens(),
				command.linesAdded(), command.linesRemoved(), cost, command.eventTime()));

		rollupService.apply(event);
		analyticsCacheService.evictOrg(member.getOrgId());
		return event;
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
