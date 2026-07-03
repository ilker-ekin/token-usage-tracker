package dev.ekin.tokenusage.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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

	@PostMapping("/token")
	public TokenResponse token(@RequestBody @Validated TokenRequest request) {
		String token = authService.authenticate(request.email(), request.password());
		return new TokenResponse(token, "Bearer", jwtService.ttlSeconds());
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal MemberPrincipal principal) {
		return new MeResponse(principal.memberId(), principal.orgId(),
				principal.email(), principal.roles());
	}

}