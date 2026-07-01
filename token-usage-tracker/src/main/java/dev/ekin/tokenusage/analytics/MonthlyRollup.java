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
@Table(name = "monthly_rollup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyRollup extends RollupBase {

	/** First day of the month this rollup covers. */
	@Column(name = "month_start", nullable = false)
	private LocalDate monthStart;

	public MonthlyRollup(UUID orgId, UUID teamId, UUID memberId, UUID projectId, LocalDate monthStart,
			long totalInputTokens, long totalOutputTokens, BigDecimal totalCostUsd,
			long totalLinesAdded, long totalLinesRemoved) {
		super(orgId, teamId, memberId, projectId, totalInputTokens, totalOutputTokens, totalCostUsd,
				totalLinesAdded, totalLinesRemoved);
		this.monthStart = monthStart;
	}

}
