package dev.ekin.tokenusage.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "weekly_rollup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WeeklyRollup extends RollupBase {

	/** Monday of the ISO week this rollup covers. */
	@Column(name = "week_start", nullable = false)
	private LocalDate weekStart;

	public WeeklyRollup(UUID orgId, UUID teamId, UUID memberId, UUID projectId, LocalDate weekStart,
			long totalInputTokens, long totalOutputTokens, BigDecimal totalCostUsd,
			long totalLinesAdded, long totalLinesRemoved) {
		super(orgId, teamId, memberId, projectId, totalInputTokens, totalOutputTokens, totalCostUsd,
				totalLinesAdded, totalLinesRemoved);
		this.weekStart = weekStart;
	}

}
