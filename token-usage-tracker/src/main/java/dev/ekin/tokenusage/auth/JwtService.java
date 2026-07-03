package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.tenant.Member;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {

	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;

	public JwtService(JwtEncoder jwtEncoder, JwtProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public String issueToken(Member member) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(member.getId().toString())
				.claim("org", member.getOrgId().toString())
				.claim("email", member.getEmail())
				.claim("roles", member.roleList())
				.issuedAt(now)
				.expiresAt(now.plus(properties.ttl()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public long ttlSeconds() {
		return properties.ttl().toSeconds();
	}

}