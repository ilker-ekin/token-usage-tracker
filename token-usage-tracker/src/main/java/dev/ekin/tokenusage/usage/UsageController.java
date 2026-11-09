package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.auth.MemberPrincipal;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import dev.ekin.tokenusage.usage.UsageService.IngestResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

	/** Far above any single model call; one event beyond it is a client bug. */
	static final long MAX_TOKENS = 100_000_000;
	static final long MAX_LINES = 10_000_000;
	/** Width of usage_event.model. */
	static final int MAX_MODEL_LENGTH = 100;

	/**
	 * {@code inputTokens} is uncached input only. The optional cache fields
	 * default to 0, so clients that do not use prompt caching are unaffected.
	 */
	public record UsageEventRequest(
			@NotNull UUID projectId,
			@NotBlank @Size(max = MAX_MODEL_LENGTH) String model,
			@PositiveOrZero @Max(MAX_TOKENS) int inputTokens,
			@PositiveOrZero @Max(MAX_TOKENS) int outputTokens,
			@PositiveOrZero @Max(MAX_TOKENS) Integer cacheReadTokens,
			@PositiveOrZero @Max(MAX_TOKENS) Integer cacheCreationTokens,
			@PositiveOrZero @Max(MAX_LINES) Integer linesAdded,
			@PositiveOrZero @Max(MAX_LINES) Integer linesRemoved,
			@NotNull Instant eventTime) {
	}

	public record UsageEventResponse(UUID id, BigDecimal costUsd) {
	}

	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
	static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;

	/**
	 * 201 for a new event. With an {@code Idempotency-Key} the org has already
	 * used, 200 and the event booked the first time; nothing is booked again.
	 */
	@PostMapping
	public ResponseEntity<UsageEventResponse> ingest(@AuthenticationPrincipal MemberPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
			@RequestBody @Validated UsageEventRequest request) {
		if (idempotencyKey != null && (idempotencyKey.isBlank()
				|| idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					IDEMPOTENCY_KEY_HEADER + " must be 1 to " + IDEMPOTENCY_KEY_MAX_LENGTH + " characters");
		}
		IngestResult result = usageService.ingest(principal.memberId(), new IngestCommand(
				request.projectId(), request.model(), request.inputTokens(), request.outputTokens(),
				orZero(request.cacheReadTokens()), orZero(request.cacheCreationTokens()),
				request.linesAdded(), request.linesRemoved(), request.eventTime()), idempotencyKey);
		UsageEvent event = result.event();
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(new UsageEventResponse(event.getId(), event.getCostUsd()));
	}

	public record UsageEventDetail(UUID id, UUID memberId, UUID teamId, UUID projectId,
			String model, int inputTokens, int outputTokens, int cacheReadTokens,
			int cacheCreationTokens, Integer linesAdded,
			Integer linesRemoved, BigDecimal costUsd, Instant eventTime) {

		static UsageEventDetail from(UsageEvent e) {
			return new UsageEventDetail(e.getId(), e.getMemberId(), e.getTeamId(), e.getProjectId(),
					e.getModel(), e.getInputTokens(), e.getOutputTokens(), e.getCacheReadTokens(),
					e.getCacheCreationTokens(), e.getLinesAdded(),
					e.getLinesRemoved(), e.getCostUsd(), e.getEventTime());
		}
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
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