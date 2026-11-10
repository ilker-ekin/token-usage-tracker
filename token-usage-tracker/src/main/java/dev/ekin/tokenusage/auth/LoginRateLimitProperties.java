package dev.ekin.tokenusage.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxFailuresPerEmail failed logins for one email before it is locked
 *                            out for the rest of the window.
 * @param maxFailuresPerIp    failed logins from one client address, across
 *                            all emails, before that address is locked out.
 * @param window              how long failures are remembered, counted from
 *                            the first one.
 */
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public record LoginRateLimitProperties(Integer maxFailuresPerEmail, Integer maxFailuresPerIp,
		Duration window) {

	public LoginRateLimitProperties {
		maxFailuresPerEmail = maxFailuresPerEmail != null ? maxFailuresPerEmail : 5;
		maxFailuresPerIp = maxFailuresPerIp != null ? maxFailuresPerIp : 20;
		window = window != null ? window : Duration.ofMinutes(15);
	}

}
