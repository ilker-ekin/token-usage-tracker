package dev.ekin.tokenusage.config;

import dev.ekin.tokenusage.auth.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

	private static final String DEV_DEFAULT = "local-dev-only-secret-change-me-0123456789abcdef";
	private static final String REAL_SECRET = "a-properly-provisioned-secret-0123456789abcdef";

	private final JwtConfig jwtConfig = new JwtConfig();

	private static JwtProperties props(String secret) {
		return new JwtProperties(secret, Duration.ofHours(1));
	}

	@Test
	void refusesDevDefaultSecretOutsideDevProfile() {
		assertThatThrownBy(() -> jwtConfig.jwtSecretKey(props(DEV_DEFAULT), new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JWT_SECRET");
	}

	@Test
	void allowsDevDefaultSecretUnderDevProfile() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("dev");
		assertThat(jwtConfig.jwtSecretKey(props(DEV_DEFAULT), env)).isNotNull();
	}

	@Test
	void allowsProvisionedSecretWithoutAnyProfile() {
		assertThat(jwtConfig.jwtSecretKey(props(REAL_SECRET), new MockEnvironment())).isNotNull();
	}

	@Test
	void refusesSecretShorterThan32Bytes() {
		assertThatThrownBy(() -> jwtConfig.jwtSecretKey(props("too-short"), new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 bytes");
	}

}
