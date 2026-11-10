package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.tenant.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
