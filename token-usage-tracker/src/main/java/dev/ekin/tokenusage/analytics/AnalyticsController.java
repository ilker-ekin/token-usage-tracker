package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.analytics.AnalyticsService.DailyAnalytics;
import dev.ekin.tokenusage.analytics.AnalyticsService.MemberAnalytics;
import dev.ekin.tokenusage.analytics.AnalyticsService.ModelAnalytics;
import dev.ekin.tokenusage.analytics.AnalyticsService.SummaryAnalytics;
import dev.ekin.tokenusage.analytics.AnalyticsService.ProjectAnalytics;
import dev.ekin.tokenusage.analytics.AnalyticsService.TeamAnalytics;
import dev.ekin.tokenusage.auth.MemberPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Org-scoped analytics for the admin dashboard. The org is always taken
 * from the JWT, never from the request. Defaults to the last 30 days.
 */
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

	private final AnalyticsService analyticsService;

	public AnalyticsController(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	@GetMapping("/projects")
	public List<ProjectAnalytics> projects(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate effectiveTo = effectiveTo(to);
		return analyticsService.projects(principal.orgId(), effectiveFrom(from, effectiveTo), effectiveTo);
	}

	@GetMapping("/members")
	public List<MemberAnalytics> members(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate effectiveTo = effectiveTo(to);
		return analyticsService.members(principal.orgId(), effectiveFrom(from, effectiveTo), effectiveTo);
	}

	@GetMapping("/teams")
	public List<TeamAnalytics> teams(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate effectiveTo = effectiveTo(to);
		return analyticsService.teams(principal.orgId(), effectiveFrom(from, effectiveTo), effectiveTo);
	}

	@GetMapping("/daily")
	public List<DailyAnalytics> daily(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate effectiveTo = effectiveTo(to);
		return analyticsService.daily(principal.orgId(), effectiveFrom(from, effectiveTo), effectiveTo);
	}

	/** Spend per model; with {@code projectId}, for that project only (another org's project returns nothing). */
	@GetMapping("/models")
	public List<ModelAnalytics> models(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID projectId) {
		LocalDate effectiveTo = effectiveTo(to);
		LocalDate effectiveFrom = effectiveFrom(from, effectiveTo);
		requireOrdered(effectiveFrom, effectiveTo);
		return analyticsService.models(principal.orgId(), effectiveFrom, effectiveTo, projectId);
	}

	/** Totals for the range and for the previous range of equal length, for comparison deltas. */
	@GetMapping("/summary")
	public SummaryAnalytics summary(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate effectiveTo = effectiveTo(to);
		LocalDate effectiveFrom = effectiveFrom(from, effectiveTo);
		requireOrdered(effectiveFrom, effectiveTo);
		return analyticsService.summary(principal.orgId(), effectiveFrom, effectiveTo);
	}

	private static void requireOrdered(LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
		}
	}

	private static LocalDate effectiveTo(LocalDate to) {
		return to != null ? to : LocalDate.now(ZoneOffset.UTC);
	}

	private static LocalDate effectiveFrom(LocalDate from, LocalDate to) {
		return from != null ? from : to.minusDays(29);
	}

}
