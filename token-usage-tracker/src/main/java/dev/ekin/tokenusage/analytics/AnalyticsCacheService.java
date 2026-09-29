package dev.ekin.tokenusage.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Read-through cache for analytics results with a 5-minute TTL. Redis being
 * down never fails a request — the cache silently degrades to a pass-through.
 *
 * <p>Eviction is by versioning, not deletion. Every key embeds a global
 * generation and a per-org version:
 * {@code analytics:{orgId}:{gen}.{ver}:{dimension}:{from}:{to}}. Evicting an
 * org is one {@code INCR} of its version; evicting everything is one
 * {@code INCR} of the generation. Old entries are never read again and age
 * out through their TTL. Unlike KEYS (or SCAN) + DEL this is O(1), and it
 * cannot resurrect stale data: a reader resolves the version before querying
 * the database, so a result computed before a write committed is stored
 * under the old version and never served.
 *
 * <p>Bumps run after the surrounding transaction commits, so no reader can
 * cache pre-commit data under the new version.
 */
@Service
public class AnalyticsCacheService {

	static final Duration TTL = Duration.ofMinutes(5);
	static final String GENERATION_KEY = "analytics:gen";

	private static final Logger log = LoggerFactory.getLogger(AnalyticsCacheService.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public AnalyticsCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	static String versionKey(UUID orgId) {
		return "analytics:ver:" + orgId;
	}

	/**
	 * Returns the cached result for this org/dimension/range, or runs
	 * {@code loader} and caches what it returns.
	 */
	public <T> List<T> getOrLoad(UUID orgId, String dimension, LocalDate from, LocalDate to,
			Class<T[]> arrayType, Supplier<List<T>> loader) {
		String key = currentKey(orgId, dimension, from, to);
		if (key == null) {
			return loader.get();
		}
		List<T> cached = read(key, arrayType);
		if (cached != null) {
			return cached;
		}
		List<T> result = loader.get();
		write(key, result);
		return result;
	}

	/** Invalidates everything cached for the org once the current transaction commits. */
	public void evictOrg(UUID orgId) {
		afterCommit(() -> increment(versionKey(orgId)));
	}

	/** Invalidates every org's cache once the current transaction commits. */
	public void evictAll() {
		afterCommit(() -> increment(GENERATION_KEY));
	}

	/** Null when Redis is unreachable: the caller skips the cache entirely. */
	private String currentKey(UUID orgId, String dimension, LocalDate from, LocalDate to) {
		try {
			List<String> versions = redisTemplate.opsForValue()
					.multiGet(List.of(GENERATION_KEY, versionKey(orgId)));
			return "analytics:%s:%s.%s:%s:%s:%s".formatted(orgId,
					orZero(versions.get(0)), orZero(versions.get(1)), dimension, from, to);
		} catch (Exception e) {
			log.warn("Analytics cache version lookup failed for org {}: {}", orgId, e.getMessage());
			return null;
		}
	}

	private <T> List<T> read(String key, Class<T[]> arrayType) {
		try {
			String json = redisTemplate.opsForValue().get(key);
			return json == null ? null : Arrays.asList(objectMapper.readValue(json, arrayType));
		} catch (Exception e) {
			log.warn("Analytics cache read failed for {}: {}", key, e.getMessage());
			return null;
		}
	}

	private void write(String key, Object value) {
		try {
			redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
		} catch (Exception e) {
			log.warn("Analytics cache write failed for {}: {}", key, e.getMessage());
		}
	}

	private void increment(String key) {
		try {
			redisTemplate.opsForValue().increment(key);
		} catch (Exception e) {
			log.warn("Analytics cache eviction failed for {}: {}", key, e.getMessage());
		}
	}

	private static void afterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}

	private static String orZero(String version) {
		return version == null ? "0" : version;
	}

}
