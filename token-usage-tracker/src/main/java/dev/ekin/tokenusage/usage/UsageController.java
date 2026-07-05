package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.auth.MemberPrincipal;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/usage")
public class UsageController {

	private final UsageService usageService;

	public UsageController(UsageService usageService) {
		this.usageService = usageService;
	}

	public record UsageEventRequest(
			@NotNull UUID projectId,
			@NotBlank String model,
			@PositiveOrZero int inputTokens,
			@PositiveOrZero int outputTokens,
			@PositiveOrZero Integer linesAdded,
			@PositiveOrZero Integer linesRemoved,
			@NotNull Instant eventTime) {
	}

	public record UsageEventResponse(UUID id, BigDecimal costUsd) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UsageEventResponse ingest(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestBody @Validated UsageEventRequest request) {
		UsageEvent event = usageService.ingest(principal.memberId(), new IngestCommand(
				request.projectId(), request.model(), request.inputTokens(), request.outputTokens(),
				request.linesAdded(), request.linesRemoved(), request.eventTime()));
		return new UsageEventResponse(event.getId(), event.getCostUsd());
	}

	public record UsageEventDetail(UUID id, UUID memberId, UUID teamId, UUID projectId,
			String model, int inputTokens, int outputTokens, Integer linesAdded,
			Integer linesRemoved, BigDecimal costUsd, Instant eventTime) {

		static UsageEventDetail from(UsageEvent e) {
			return new UsageEventDetail(e.getId(), e.getMemberId(), e.getTeamId(), e.getProjectId(),
					e.getModel(), e.getInputTokens(), e.getOutputTokens(), e.getLinesAdded(),
					e.getLinesRemoved(), e.getCostUsd(), e.getEventTime());
		}
	}

	public record PageResponse<T>(List<T> content, int page, int size,
			long totalElements, int totalPages) {
	}

	/** Raw event drill-down, always scoped to the caller's org. */
	@GetMapping
	public PageResponse<UsageEventDetail> list(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestParam(required = false) UUID memberId,
			@RequestParam(required = false) UUID projectId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		Page<UsageEvent> events = usageService.search(principal.orgId(),
				memberId, projectId, from, to, page, Math.min(size, 200));
		return new PageResponse<>(events.getContent().stream().map(UsageEventDetail::from).toList(),
				events.getNumber(), events.getSize(), events.getTotalElements(), events.getTotalPages());
	}

}