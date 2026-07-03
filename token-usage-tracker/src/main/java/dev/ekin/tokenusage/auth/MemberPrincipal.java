package dev.ekin.tokenusage.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated caller, reconstructed from JWT claims on every request.
 * Carries the org id so services can scope queries without a DB lookup.
 */
public record MemberPrincipal(UUID memberId, UUID orgId, String email, List<String> roles) {

	static MemberPrincipal fromClaims(Jwt jwt) {
		return new MemberPrincipal(
				UUID.fromString(jwt.getSubject()),
				UUID.fromString(jwt.getClaimAsString("org")),
				jwt.getClaimAsString("email"),
				jwt.getClaimAsStringList("roles"));
	}

	public List<GrantedAuthority> authorities() {
		return roles.stream()
				.map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
				.toList();
	}

}