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
@Table(name = "daily_rollup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRollup extends RollupBase {

	@Column(name = "rollup_date", nullable = false)
	private LocalDate rollupDate;

	public DailyRollup(UUID orgId, UUID teamId, UUID memberId, UUID projectId, LocalDate rollupDate,
			long totalInputTokens, long totalOutputTokens, BigDecimal totalCostUsd,
			long totalLinesAdded, long totalLinesRemoved) {
		super(orgId, teamId, memberId, projectId, totalInputTokens, totalOutputTokens, totalCostUsd,
				totalLinesAdded, totalLinesRemoved);
		this.rollupDate = rollupDate;
	}

}
