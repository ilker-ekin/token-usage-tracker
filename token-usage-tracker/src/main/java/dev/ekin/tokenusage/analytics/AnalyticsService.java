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

	private final DailyRollupRepository dailyRollupRepository;
	private final MemberRepository memberRepository;
	private final AnalyticsCacheService cache;

	public AnalyticsService(DailyRollupRepository dailyRollupRepository,
			MemberRepository memberRepository, AnalyticsCacheService cache) {
		this.dailyRollupRepository = dailyRollupRepository;
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
		String key = AnalyticsCacheService.key(orgId, "projects", from, to);
		return cache.getList(key, ProjectAnalytics[].class).orElseGet(() -> {
			List<ProjectAnalytics> result = dailyRollupRepository
					.aggregateByProject(orgId, from, to).stream()
					.map(a -> new ProjectAnalytics(a.getProjectId(), a.getName(),
							a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
							a.getLinesAdded(), a.getLinesRemoved()))
					.toList();
			cache.put(key, result);
			return result;
		});
	}

	@Transactional(readOnly = true)
	public List<MemberAnalytics> members(UUID orgId, LocalDate from, LocalDate to) {
		String key = AnalyticsCacheService.key(orgId, "members", from, to);
		return cache.getList(key, MemberAnalytics[].class).orElseGet(() -> {
			List<MemberAnalytics> result = dailyRollupRepository
					.aggregateByMember(orgId, from, to).stream()
					.map(a -> new MemberAnalytics(a.getMemberId(), a.getDisplayName(), a.getEmail(),
							a.getInputTokens(), a.getOutputTokens(), a.getCostUsd(),
							a.getLinesAdded(), a.getLinesRemoved()))
					.toList();
			cache.put(key, result);
			return result;
		});
	}

	@Transactional(readOnly = true)
	public List<TeamAnalytics> teams(UUID orgId, LocalDate from, LocalDate to) {
		String key = AnalyticsCacheService.key(orgId, "teams", from, to);
		return cache.getList(key, TeamAnalytics[].class).orElseGet(() -> {
			Map<UUID, Long> memberCounts = memberRepository.countByTeam(orgId).stream()
					.collect(java.util.stream.Collectors.toMap(
							MemberRepository.TeamMemberCount::getTeamId,
							MemberRepository.TeamMemberCount::getMemberCount));
			List<TeamAnalytics> result = dailyRollupRepository
					.aggregateByTeam(orgId, from, to).stream()
					.map(a -> new TeamAnalytics(a.getTeamId(), a.getName(),
							memberCounts.getOrDefault(a.getTeamId(), 0L),
							a.getInputTokens(), a.getOutputTokens(), a.getCostUsd()))
					.toList();
			cache.put(key, result);
			return result;
		});
	}

	@Transactional(readOnly = true)
	public List<DailyAnalytics> daily(UUID orgId, LocalDate from, LocalDate to) {
		String key = AnalyticsCacheService.key(orgId, "daily", from, to);
		return cache.getList(key, DailyAnalytics[].class).orElseGet(() -> {
			List<DailyAnalytics> result = dailyRollupRepository
					.aggregateByDay(orgId, from, to).stream()
					.map(a -> new DailyAnalytics(a.getDate(), a.getInputTokens(),
							a.getOutputTokens(), a.getCostUsd()))
					.toList();
			cache.put(key, result);
			return result;
		});
	}

}
