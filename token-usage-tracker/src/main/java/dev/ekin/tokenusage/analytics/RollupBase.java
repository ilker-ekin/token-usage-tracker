package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared dimensions and totals for the daily/weekly/monthly rollup tables;
 * subclasses contribute only the period column.
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RollupBase extends BaseEntity {

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "team_id")
	private UUID teamId;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "project_id", nullable = false)
	private UUID projectId;

	@Column(name = "total_input_tokens", nullable = false)
	private long totalInputTokens;

	@Column(name = "total_output_tokens", nullable = false)
	private long totalOutputTokens;

	@Column(name = "total_cost_usd", nullable = false)
	private BigDecimal totalCostUsd;

	@Column(name = "total_lines_added", nullable = false)
	private long totalLinesAdded;

	@Column(name = "total_lines_removed", nullable = false)
	private long totalLinesRemoved;

	protected RollupBase(UUID orgId, UUID teamId, UUID memberId, UUID projectId,
			long totalInputTokens, long totalOutputTokens, BigDecimal totalCostUsd,
			long totalLinesAdded, long totalLinesRemoved) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.memberId = memberId;
		this.projectId = projectId;
		this.totalInputTokens = totalInputTokens;
		this.totalOutputTokens = totalOutputTokens;
		this.totalCostUsd = totalCostUsd;
		this.totalLinesAdded = totalLinesAdded;
		this.totalLinesRemoved = totalLinesRemoved;
	}

	public void accumulate(long inputTokens, long outputTokens, BigDecimal costUsd,
			long linesAdded, long linesRemoved) {
		this.totalInputTokens += inputTokens;
		this.totalOutputTokens += outputTokens;
		this.totalCostUsd = this.totalCostUsd.add(costUsd);
		this.totalLinesAdded += linesAdded;
		this.totalLinesRemoved += linesRemoved;
	}

}
