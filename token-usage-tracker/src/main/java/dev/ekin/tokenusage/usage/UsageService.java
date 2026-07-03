package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.analytics.RollupService;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.PricingService;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
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

	public UsageService(UsageEventRepository usageEventRepository,
			MemberRepository memberRepository, PricingService pricingService,
			RollupService rollupService) {
		this.usageEventRepository = usageEventRepository;
		this.memberRepository = memberRepository;
		this.pricingService = pricingService;
		this.rollupService = rollupService;
	}

	public record IngestCommand(UUID projectId, String model, int inputTokens, int outputTokens,
			Integer linesAdded, Integer linesRemoved, Instant eventTime) {
	}

	/**
	 * Persists the event with its cost snapshotted at ingest time and updates
	 * all three rollups in the same transaction.
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
		return event;
	}

}