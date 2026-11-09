package dev.ekin.tokenusage.usage;

import dev.ekin.tokenusage.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageEvent extends BaseEntity {

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "team_id")
	private UUID teamId;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "project_id", nullable = false)
	private UUID projectId;

	@Column(nullable = false)
	private String model;

	/** Uncached input only; cache reads and writes are counted separately. */
	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	/** Tokens served from the provider's prompt cache. */
	@Column(name = "cache_read_tokens", nullable = false)
	private int cacheReadTokens;

	/** Tokens written to the provider's prompt cache. */
	@Column(name = "cache_creation_tokens", nullable = false)
	private int cacheCreationTokens;

	@Column(name = "lines_added")
	private Integer linesAdded;

	@Column(name = "lines_removed")
	private Integer linesRemoved;

	@Column(name = "cost_usd", nullable = false)
	private BigDecimal costUsd;

	@Column(name = "event_time", nullable = false)
	private Instant eventTime;

	public UsageEvent(UUID orgId, UUID teamId, UUID memberId, UUID projectId, String model,
			int inputTokens, int outputTokens, int cacheReadTokens, int cacheCreationTokens,
			Integer linesAdded, Integer linesRemoved, BigDecimal costUsd, Instant eventTime) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.memberId = memberId;
		this.projectId = projectId;
		this.model = model;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.cacheReadTokens = cacheReadTokens;
		this.cacheCreationTokens = cacheCreationTokens;
		this.linesAdded = linesAdded;
		this.linesRemoved = linesRemoved;
		this.costUsd = costUsd;
		this.eventTime = eventTime;
	}

}
