package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The command guards itself, so callers that bypass the HTTP layer are covered too. */
class IngestCommandTest {

	private static final UUID PROJECT = UUID.randomUUID();
	private static final Instant TIME = Instant.parse("2026-06-15T10:30:00Z");

	@Test
	void acceptsZeroesAndMissingLineCounts() {
		assertThatCode(() -> new IngestCommand(PROJECT, "m", 0, 0, 0, 0, null, null, TIME))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsEachNegativeCount() {
		assertRejected(() -> new IngestCommand(PROJECT, "m", -1, 0, 0, 0, 0, 0, TIME), "inputTokens");
		assertRejected(() -> new IngestCommand(PROJECT, "m", 0, -1, 0, 0, 0, 0, TIME), "outputTokens");
		assertRejected(() -> new IngestCommand(PROJECT, "m", 0, 0, -1, 0, 0, 0, TIME), "cacheReadTokens");
		assertRejected(() -> new IngestCommand(PROJECT, "m", 0, 0, 0, -1, 0, 0, TIME), "cacheCreationTokens");
		assertRejected(() -> new IngestCommand(PROJECT, "m", 0, 0, 0, 0, -1, 0, TIME), "linesAdded");
		assertRejected(() -> new IngestCommand(PROJECT, "m", 0, 0, 0, 0, 0, -1, TIME), "linesRemoved");
	}

	@Test
	void rejectsMissingRequiredFields() {
		assertRejected(() -> new IngestCommand(null, "m", 0, 0, null, null, TIME), "required");
		assertRejected(() -> new IngestCommand(PROJECT, " ", 0, 0, null, null, TIME), "required");
		assertRejected(() -> new IngestCommand(PROJECT, "m", 0, 0, null, null, null), "required");
	}

	private static void assertRejected(Runnable construction, String messagePart) {
		assertThatThrownBy(construction::run)
				.isInstanceOf(InvalidUsageEventException.class)
				.hasMessageContaining(messagePart);
	}

}
