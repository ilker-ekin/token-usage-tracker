package dev.ekin.tokenusage.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

	private final AuthService authService;
	private final JwtService jwtService;

	public AuthController(AuthService authService, JwtService jwtService) {
		this.authService = authService;
		this.jwtService = jwtService;
	}

	public record TokenRequest(@NotBlank @Email String email, @NotBlank String password) {
	}

	public record TokenResponse(String token, String tokenType, long expiresInSeconds) {
	}

	public record MeResponse(UUID memberId, UUID orgId, String email, List<String> roles) {
	}

	/**
	 * The client address is the socket peer, or the forwarded address when the
	 * request came through a trusted proxy (server.forward-headers-strategy).
	 */
	@PostMapping("/token")
	public TokenResponse token(@RequestBody @Validated TokenRequest request,
			HttpServletRequest httpRequest) {
		String token = authService.authenticate(request.email(), request.password(),
				httpRequest.getRemoteAddr());
		return new TokenResponse(token, "Bearer", jwtService.ttlSeconds());
	}

	@ExceptionHandler(TooManyLoginAttemptsException.class)
	ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
						"Too many failed login attempts; try again later"));
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal MemberPrincipal principal) {
		return new MeResponse(principal.memberId(), principal.orgId(),
				principal.email(), principal.roles());
	}

}