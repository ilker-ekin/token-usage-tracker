package dev.ekin.tokenusage.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-through cache for analytics results. Keys follow
 * {@code analytics:{orgId}:{dimension}:{from}:{to}} with a 5-minute TTL;
 * writes to an org's rollups evict that org's keys. Redis being down never
 * fails a request — the cache silently degrades to a pass-through.
 */
@Service
public class AnalyticsCacheService {

	static final Duration TTL = Duration.ofMinutes(5);

	private static final Logger log = LoggerFactory.getLogger(AnalyticsCacheService.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public AnalyticsCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	public static String key(UUID orgId, String dimension, Object from, Object to) {
		return "analytics:%s:%s:%s:%s".formatted(orgId, dimension, from, to);
	}

	public <T> Optional<List<T>> getList(String key, Class<T[]> arrayType) {
		try {
			String json = redisTemplate.opsForValue().get(key);
			if (json == null) {
				return Optional.empty();
			}
			return Optional.of(Arrays.asList(objectMapper.readValue(json, arrayType)));
		} catch (Exception e) {
			log.warn("Analytics cache read failed for {}: {}", key, e.getMessage());
			return Optional.empty();
		}
	}

	public void put(String key, Object value) {
		try {
			redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
		} catch (Exception e) {
			log.warn("Analytics cache write failed for {}: {}", key, e.getMessage());
		}
	}

	public void evictOrg(UUID orgId) {
		evictPattern("analytics:" + orgId + ":*");
	}

	public void evictAll() {
		evictPattern("analytics:*");
	}

	private void evictPattern(String pattern) {
		try {
			Set<String> keys = redisTemplate.keys(pattern);
			if (!keys.isEmpty()) {
				redisTemplate.delete(keys);
			}
		} catch (Exception e) {
			log.warn("Analytics cache eviction failed for {}: {}", pattern, e.getMessage());
		}
	}

}
