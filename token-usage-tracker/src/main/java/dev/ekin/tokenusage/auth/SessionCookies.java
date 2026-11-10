package dev.ekin.tokenusage.auth;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the session cookie for the dashboard. HttpOnly keeps the token out
 * of reach of page scripts; SameSite=Strict keeps the browser from sending it
 * on requests started by another site.
 */
@Component
public class SessionCookies {

	private final SessionCookieProperties properties;

	public SessionCookies(SessionCookieProperties properties) {
		this.properties = properties;
	}

	public String name() {
		return properties.name();
	}

	public ResponseCookie issue(String token, long maxAgeSeconds) {
		return base(token).maxAge(Duration.ofSeconds(maxAgeSeconds)).build();
	}

	public ResponseCookie clear() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(properties.name(), value)
				.httpOnly(true)
				.secure(properties.secure())
				.sameSite("Strict")
				.path("/");
	}

}
