package dev.ekin.tokenusage.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Limits password guessing on the token endpoint with two fixed-window
 * counters in Redis: failed logins per email, and failed logins per client
 * address across all emails. The first slows down guessing one account, the
 * second slows down trying one password against many accounts.
 *
 * <p>Fails open: if Redis is unreachable, logins are not limited. Failing
 * closed would turn a cache outage into nobody being able to log in, the
 * same trade the analytics cache makes.
 *
 * <p>A lockout is a window, not a permanent lock: anyone who knows an email
 * can lock that account for the window by failing on purpose, and it then
 * clears by itself.
 */
@Service
public class LoginRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

	private final StringRedisTemplate redisTemplate;
	private final LoginRateLimitProperties properties;

	public LoginRateLimiter(StringRedisTemplate redisTemplate, LoginRateLimitProperties properties) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	static String emailKey(String email) {
		return "login:fail:email:" + email.trim().toLowerCase(Locale.ROOT);
	}

	static String ipKey(String clientIp) {
		return "login:fail:ip:" + clientIp;
	}

	/**
	 * Called before the password is looked at, so a locked-out caller learns
	 * nothing about whether a guess was right.
	 *
	 * @throws TooManyLoginAttemptsException when either counter is at its limit
	 */
	public void check(String email, String clientIp) {
		try {
			requireBelow(emailKey(email), properties.maxFailuresPerEmail());
			requireBelow(ipKey(clientIp), properties.maxFailuresPerIp());
		} catch (TooManyLoginAttemptsException e) {
			throw e;
		} catch (Exception e) {
			log.warn("Login rate limit check skipped, Redis unavailable: {}", e.getMessage());
		}
	}

	public void recordFailure(String email, String clientIp) {
		try {
			increment(emailKey(email));
			increment(ipKey(clientIp));
		} catch (Exception e) {
			log.warn("Login failure not counted, Redis unavailable: {}", e.getMessage());
		}
	}

	/**
	 * Clears the email's counter. The address counter is kept: one valid
	 * login must not reset an address that is guessing other accounts.
	 */
	public void recordSuccess(String email) {
		try {
			redisTemplate.delete(emailKey(email));
		} catch (Exception e) {
			log.warn("Login counter not reset, Redis unavailable: {}", e.getMessage());
		}
	}

	private void requireBelow(String key, int limit) {
		String value = redisTemplate.opsForValue().get(key);
		if (value != null && Long.parseLong(value) >= limit) {
			Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
			throw new TooManyLoginAttemptsException(
					ttl != null && ttl > 0 ? ttl : properties.window().toSeconds());
		}
	}

	private void increment(String key) {
		Long count = redisTemplate.opsForValue().increment(key);
		Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
		// The window starts at the first failure. Also repairs a counter left
		// without an expiry, which would otherwise lock the key out for good.
		if ((count != null && count == 1) || ttl == null || ttl < 0) {
			redisTemplate.expire(key, properties.window());
		}
	}

}
