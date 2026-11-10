package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.tenant.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalyticsService {

	private final RollupRepository rollupRepository;
	private final MemberRepository memberRepository;
	private final AnalyticsCacheService cache;

	public AnalyticsService(RollupRepository rollupRepository,
			MemberRepository memberRepository, AnalyticsCacheService cache) {
		this.rollupRepository = rollupRepository;
		this.memberRepository = memberRepository;
		this.cache = cache;
	}

	public record ProjectAnalytics(UUID projectId, String name, long inputTokens,
			long outputTokens, BigDecimal costUsd, long linesAdded, long linesRemoved) {
	}

	public record MemberAnalytics(UUID memberId, String displayName, String email,
			long inputTokens, long outputTokens, BigDecimal costUsd,
			long linesAdded, long linesRemoved) {
	}

	public record TeamAnalytics(UUID teamId, String name, long memberCount,
			long inputTokens, long outputTokens, BigDecimal costUsd) {
	}

	public record DailyAnalytics(LocalDate date, long inputTokens, long outputTokens,
			BigDecimal costUsd) {
	}

	@Transactional(readOnly = true)
	public List<ProjectAnalytics> projects(UUID orgId, LocalDate from, LocalDate to) {
		return cache.getOrLoad(orgId, "projects", from, to, ProjectAnalytics[].class, () -> {
			List<ProjectAnalytics> result = rollupRepository
					.aggregateByProject(orgId, from, to).stream()
					.map(a -> new ProjectAnalytics(a.getProjectId(), a.getName(),
							a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
							a.getLinesAdded(), a.getLinesRemoved()))
					.toList();
			return result;
		});
	}

	@Transactional(readOnly = true)
	public List<MemberAnalytics> members(UUID orgId, LocalDate from, LocalDate to) {
		return cache.getOrLoad(orgId, "members", from, to, MemberAnalytics[].class, () -> {
			List<MemberAnalytics> result = rollupRepository
					.aggregateByMember(orgId, from, to).stream()
					.map(a -> new MemberAnalytics(a.getMemberId(), a.getDisplayName(), a.getEmail(),
							a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
							a.getLinesAdded(), a.getLinesRemoved()))
					.toList();
			return result;
		});
	}

	@Transactional(readOnly = true)
	public List<TeamAnalytics> teams(UUID orgId, LocalDate from, LocalDate to) {
		return cache.getOrLoad(orgId, "teams", from, to, TeamAnalytics[].class, () -> {
			Map<UUID, Long> memberCounts = memberRepository.countByTeam(orgId).stream()
					.collect(java.util.stream.Collectors.toMap(
							MemberRepository.TeamMemberCount::getTeamId,
							MemberRepository.TeamMemberCount::getMemberCount));
			List<TeamAnalytics> result = rollupRepository
					.aggregateByTeam(orgId, from, to).stream()
					.map(a -> new TeamAnalytics(a.getTeamId(), a.getName(),
							memberCounts.getOrDefault(a.getTeamId(), 0L),
							a.getInputTokens(), a.getOutputTokens(), a.getCostUsd()))
					.toList();
			return result;
		});
	}

	public record ModelAnalytics(String model, long inputTokens, long outputTokens,
			long cacheReadTokens, long cacheCreationTokens, BigDecimal costUsd) {
	}

	public record Totals(long inputTokens, long outputTokens, long cacheReadTokens,
			long cacheCreationTokens, BigDecimal costUsd, long linesAdded, long linesRemoved) {
	}

	/**
	 * Totals for a range and for the previous range of equal length, which
	 * ends the day before {@code from}. Both ranges are inclusive, in UTC days.
	 */
	public record SummaryAnalytics(LocalDate from, LocalDate to, Totals current,
			LocalDate previousFrom, LocalDate previousTo, Totals previous) {
	}

	/** Spend per model, most expensive first; for one project when {@code projectId} is set. */
	@Transactional(readOnly = true)
	public List<ModelAnalytics> models(UUID orgId, LocalDate from, LocalDate to, UUID projectId) {
		String dimension = projectId == null ? "models" : "models:" + projectId;
		return cache.getOrLoad(orgId, dimension, from, to, ModelAnalytics[].class, () ->
				(projectId == null
						? rollupRepository.aggregateByModel(orgId, from, to)
						: rollupRepository.aggregateByModelForProject(orgId, projectId, from, to))
						.stream()
						.map(a -> new ModelAnalytics(a.getModel(), a.getInputTokens(), a.getOutputTokens(),
								a.getCacheReadTokens(), a.getCacheCreationTokens(), a.getCostUsd()))
						.toList());
	}

	@Transactional(readOnly = true)
	public SummaryAnalytics summary(UUID orgId, LocalDate from, LocalDate to) {
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		LocalDate previousTo = from.minusDays(1);
		LocalDate previousFrom = previousTo.minusDays(days - 1);
		return cache.getOrLoad(orgId, "summary", from, to, SummaryAnalytics[].class, () ->
				List.of(new SummaryAnalytics(from, to, totals(orgId, from, to),
						previousFrom, previousTo, totals(orgId, previousFrom, previousTo))))
				.getFirst();
	}

	private Totals totals(UUID orgId, LocalDate from, LocalDate to) {
		RollupRepository.TotalsAgg t = rollupRepository.totals(orgId, from, to);
		return new Totals(t.getInputTokens(), t.getOutputTokens(), t.getCacheReadTokens(),
				t.getCacheCreationTokens(), t.getCostUsd(), t.getLinesAdded(), t.getLinesRemoved());
	}

	@Transactional(readOnly = true)
	public List<DailyAnalytics> daily(UUID orgId, LocalDate from, LocalDate to) {
		return cache.getOrLoad(orgId, "daily", from, to, DailyAnalytics[].class, () -> {
			List<DailyAnalytics> result = rollupRepository
					.aggregateByDay(orgId, from, to).stream()
					.map(a -> new DailyAnalytics(a.getDate(), a.getInputTokens(),
							a.getOutputTokens(), a.getCostUsd()))
					.toList();
			return result;
		});
	}

}
