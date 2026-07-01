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

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	@Column(name = "lines_added")
	private Integer linesAdded;

	@Column(name = "lines_removed")
	private Integer linesRemoved;

	@Column(name = "cost_usd", nullable = false)
	private BigDecimal costUsd;

	@Column(name = "event_time", nullable = false)
	private Instant eventTime;

	public UsageEvent(UUID orgId, UUID teamId, UUID memberId, UUID projectId, String model,
			int inputTokens, int outputTokens, Integer linesAdded, Integer linesRemoved,
			BigDecimal costUsd, Instant eventTime) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.memberId = memberId;
		this.projectId = projectId;
		this.model = model;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.linesAdded = linesAdded;
		this.linesRemoved = linesRemoved;
		this.costUsd = costUsd;
		this.eventTime = eventTime;
	}

}
