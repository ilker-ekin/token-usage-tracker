package dev.ekin.tokenusage.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param secret HMAC-SHA256 signing key; must be at least 32 bytes.
 * @param ttl    lifetime of issued tokens.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration ttl) {
}