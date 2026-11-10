package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Pre-aggregated usage of one member on one project with one model in one
 * period. Every event is counted in three rows: its day, its week and its
 * month.
 */
@Entity
@Table(name = "rollup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Rollup extends BaseEntity {

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "team_id")
	private UUID teamId;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "project_id", nullable = false)
	private UUID projectId;

	/** Model id exactly as reported on the events, dated snapshots included. */
	@Column(nullable = false)
	private String model;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Granularity granularity;

	/** See {@link Granularity#periodStart}. */
	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "total_input_tokens", nullable = false)
	private long totalInputTokens;

	@Column(name = "total_output_tokens", nullable = false)
	private long totalOutputTokens;

	@Column(name = "total_cache_read_tokens", nullable = false)
	private long totalCacheReadTokens;

	@Column(name = "total_cache_creation_tokens", nullable = false)
	private long totalCacheCreationTokens;

	@Column(name = "total_cost_usd", nullable = false)
	private BigDecimal totalCostUsd;

	@Column(name = "total_lines_added", nullable = false)
	private long totalLinesAdded;

	@Column(name = "total_lines_removed", nullable = false)
	private long totalLinesRemoved;

	public Rollup(UUID orgId, UUID teamId, UUID memberId, UUID projectId, String model,
			Granularity granularity, LocalDate periodStart,
			long totalInputTokens, long totalOutputTokens,
			long totalCacheReadTokens, long totalCacheCreationTokens, BigDecimal totalCostUsd,
			long totalLinesAdded, long totalLinesRemoved) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.memberId = memberId;
		this.projectId = projectId;
		this.model = model;
		this.granularity = granularity;
		this.periodStart = periodStart;
		this.totalInputTokens = totalInputTokens;
		this.totalOutputTokens = totalOutputTokens;
		this.totalCacheReadTokens = totalCacheReadTokens;
		this.totalCacheCreationTokens = totalCacheCreationTokens;
		this.totalCostUsd = totalCostUsd;
		this.totalLinesAdded = totalLinesAdded;
		this.totalLinesRemoved = totalLinesRemoved;
	}

}
