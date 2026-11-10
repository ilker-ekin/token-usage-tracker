package dev.ekin.tokenusage.auth;

/** Too many failed logins for this email or from this address; reported as 429. */
public class TooManyLoginAttemptsException extends RuntimeException {

	private final long retryAfterSeconds;

	public TooManyLoginAttemptsException(long retryAfterSeconds) {
		super("Too many failed login attempts");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
