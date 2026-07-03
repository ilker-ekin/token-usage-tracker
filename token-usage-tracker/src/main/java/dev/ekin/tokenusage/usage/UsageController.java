package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.auth.MemberPrincipal;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
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

}