package dev.ekin.tokenusage.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Populates the security context from a JWT: the {@code Authorization: Bearer}
 * header first (CLI and programmatic clients), otherwise the dashboard's
 * session cookie. Invalid or absent tokens leave the context empty, so the
 * authorization rules in SecurityConfig reject the request with 401; the
 * filter never short-circuits for that, keeping public endpoints reachable.
 *
 * <p>CSRF: a cookie is sent by the browser on its own, so a request that is
 * authenticated by the cookie and changes state must also carry
 * {@link #CSRF_HEADER}. Another site cannot add a custom header to a
 * cross-origin request without a CORS preflight, and no CORS is configured.
 * Such a request without the header is refused with 403. Bearer requests are
 * not affected: their token is never sent automatically.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	public static final String CSRF_HEADER = "X-Requested-With";

	private static final String BEARER_PREFIX = "Bearer ";
	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final JwtDecoder jwtDecoder;
	private final SessionCookies sessionCookies;

	public JwtAuthenticationFilter(JwtDecoder jwtDecoder, SessionCookies sessionCookies) {
		this.jwtDecoder = jwtDecoder;
		this.sessionCookies = sessionCookies;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			authenticate(header.substring(BEARER_PREFIX.length()));
		} else {
			String cookieToken = cookieValue(request);
			if (cookieToken != null && authenticate(cookieToken)
					&& !SAFE_METHODS.contains(request.getMethod())
					&& isBlank(request.getHeader(CSRF_HEADER))) {
				SecurityContextHolder.clearContext();
				response.setStatus(HttpStatus.FORBIDDEN.value());
				response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				response.getWriter().write("""
						{"type":"about:blank","title":"Forbidden","status":403,\
						"detail":"Cookie-authenticated requests that change data need the %s header"}\
						""".formatted(CSRF_HEADER));
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	/** @return whether the token was valid and the context is now authenticated */
	private boolean authenticate(String token) {
		try {
			Jwt jwt = jwtDecoder.decode(token);
			MemberPrincipal principal = MemberPrincipal.fromClaims(jwt);
			var authentication = new UsernamePasswordAuthenticationToken(
					principal, null, principal.authorities());
			SecurityContextHolder.getContext().setAuthentication(authentication);
			return true;
		} catch (JwtException | IllegalArgumentException e) {
			SecurityContextHolder.clearContext();
			return false;
		}
	}

	private String cookieValue(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookie.getName().equals(sessionCookies.name()) && !isBlank(cookie.getValue())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
