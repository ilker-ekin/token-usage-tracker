package dev.ekin.tokenusage.budget;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A budget crossing one threshold in one month, and the state of its webhook
 * delivery. Rows are created by {@link BudgetAlertRepository#raiseCrossedThresholds}
 * and only their delivery fields change afterwards.
 */
@Entity
@Table(name = "budget_alert")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetAlert extends BaseEntity {

	public enum Status {
		/** Waiting for a delivery attempt. */
		PENDING,
		/** Claimed by a sender; the HTTP call is in flight. */
		SENDING,
		SENT,
		/** Gave up after the configured number of attempts. */
		FAILED
	}

	@Column(name = "budget_id", nullable = false)
	private UUID budgetId;

	/** First day of the month the alert is for, UTC. */
	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "threshold_pct", nullable = false)
	private int thresholdPct;

	/** Spend when the crossing was first seen. */
	@Column(name = "spend_usd", nullable = false)
	private BigDecimal spendUsd;

	/** The budget's limit when the crossing was first seen. */
	@Column(name = "limit_usd", nullable = false)
	private BigDecimal limitUsd;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	void markSent() {
		this.attempts++;
		this.status = Status.SENT;
		this.sentAt = Instant.now();
	}

	/** Back to PENDING for the next run, or FAILED once the attempts are used up. */
	void markAttemptFailed(int maxAttempts) {
		this.attempts++;
		this.status = attempts >= maxAttempts ? Status.FAILED : Status.PENDING;
	}

}
