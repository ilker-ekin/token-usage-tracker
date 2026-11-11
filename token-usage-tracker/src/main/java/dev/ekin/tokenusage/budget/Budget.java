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
import java.util.UUID;

/** A monthly spend cap (calendar month, UTC) on one project or one team. */
@Entity
@Table(name = "budget")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Budget extends BaseEntity {

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope_type", nullable = false)
	private BudgetScope scopeType;

	/** A project id or a team id, depending on {@link #scopeType}. */
	@Column(name = "scope_id", nullable = false)
	private UUID scopeId;

	@Column(name = "monthly_limit_usd", nullable = false)
	private BigDecimal monthlyLimitUsd;

	@Column(name = "webhook_url", nullable = false)
	private String webhookUrl;

	@Column(nullable = false)
	private boolean enabled;

	/**
	 * Signs this budget's webhook requests (see {@link WebhookSigner}). The API
	 * returns it only when it is created or rotated, never in a listing.
	 */
	@Column(name = "webhook_secret", nullable = false)
	private String webhookSecret;

	public Budget(UUID orgId, BudgetScope scopeType, UUID scopeId, BigDecimal monthlyLimitUsd,
			String webhookUrl, boolean enabled) {
		this.orgId = orgId;
		this.scopeType = scopeType;
		this.scopeId = scopeId;
		this.monthlyLimitUsd = monthlyLimitUsd;
		this.webhookUrl = webhookUrl;
		this.enabled = enabled;
		this.webhookSecret = WebhookSigner.newSecret();
	}

	/** Replaces the signing secret; the old one stops working at once. @return the new secret */
	String rotateWebhookSecret() {
		this.webhookSecret = WebhookSigner.newSecret();
		return webhookSecret;
	}

	/** The scope is fixed for the life of a budget; everything else can change. */
	void update(BigDecimal monthlyLimitUsd, String webhookUrl, boolean enabled) {
		this.monthlyLimitUsd = monthlyLimitUsd;
		this.webhookUrl = webhookUrl;
		this.enabled = enabled;
	}

}
