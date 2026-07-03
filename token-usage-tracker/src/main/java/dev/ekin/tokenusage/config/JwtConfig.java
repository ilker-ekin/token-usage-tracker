package dev.ekin.tokenusage.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.ekin.tokenusage.auth.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

	/** Prefix of the committed fallback in application.yml — safe for local dev only. */
	static final String DEV_DEFAULT_SECRET_PREFIX = "local-dev-only";

	@Bean
	SecretKey jwtSecretKey(JwtProperties properties, Environment environment) {
		byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256");
		}
		// Fail fast: with the repo-committed secret anyone could forge admin
		// tokens, so refuse to start outside the dev profile.
		if (properties.secret().startsWith(DEV_DEFAULT_SECRET_PREFIX)
				&& !environment.matchesProfiles("dev")) {
			throw new IllegalStateException(
					"app.jwt.secret is the committed dev default; set JWT_SECRET "
							+ "(required outside the 'dev' profile)");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		return NimbusJwtDecoder.withSecretKey(jwtSecretKey).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}