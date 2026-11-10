package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

	private static final String IP = "203.0.113.7";

	private final MemberRepository memberRepository = mock(MemberRepository.class);
	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
	private final JwtService jwtService = mock(JwtService.class);
	private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);

	private AuthService service() {
		when(passwordEncoder.encode(any())).thenReturn("dummy-hash");
		return new AuthService(memberRepository, passwordEncoder, jwtService, rateLimiter);
	}

	private static Member member(String passwordHash) {
		return new Member(UUID.randomUUID(), null, "known@test.dev", "Known", passwordHash, "MEMBER");
	}

	/** Same hashing work as a wrong password, so timing does not reveal who has an account. */
	@Test
	void unknownEmailStillPerformsOnePasswordComparison() {
		AuthService service = service();
		when(memberRepository.findByEmail("ghost@test.dev")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.authenticate("ghost@test.dev", "pw", IP))
				.isInstanceOf(BadCredentialsException.class);

		verify(passwordEncoder, times(1)).matches("pw", "dummy-hash");
		verify(rateLimiter).recordFailure("ghost@test.dev", IP);
	}

	@Test
	void memberWithoutPasswordIsComparedAgainstTheDummyAndNeverLogsIn() {
		AuthService service = service();
		when(memberRepository.findByEmail("known@test.dev")).thenReturn(Optional.of(member(null)));
		// Even if the dummy comparison were to match, there is no password to log in with
		when(passwordEncoder.matches("pw", "dummy-hash")).thenReturn(true);

		assertThatThrownBy(() -> service.authenticate("known@test.dev", "pw", IP))
				.isInstanceOf(BadCredentialsException.class);

		verify(passwordEncoder, times(1)).matches("pw", "dummy-hash");
		verify(jwtService, never()).issueToken(any());
	}

	@Test
	void wrongPasswordPerformsOneComparisonAndCountsAFailure() {
		AuthService service = service();
		when(memberRepository.findByEmail("known@test.dev")).thenReturn(Optional.of(member("real-hash")));
		when(passwordEncoder.matches("wrong", "real-hash")).thenReturn(false);

		assertThatThrownBy(() -> service.authenticate("known@test.dev", "wrong", IP))
				.isInstanceOf(BadCredentialsException.class);

		verify(passwordEncoder, times(1)).matches("wrong", "real-hash");
		verify(rateLimiter).recordFailure("known@test.dev", IP);
		verify(rateLimiter, never()).recordSuccess(any());
	}

	@Test
	void correctPasswordIssuesATokenAndResetsTheCounter() {
		AuthService service = service();
		Member member = member("real-hash");
		when(memberRepository.findByEmail("known@test.dev")).thenReturn(Optional.of(member));
		when(passwordEncoder.matches("right", "real-hash")).thenReturn(true);
		when(jwtService.issueToken(member)).thenReturn("jwt");

		assertThat(service.authenticate("known@test.dev", "right", IP)).isEqualTo("jwt");

		verify(rateLimiter).recordSuccess("known@test.dev");
		verify(rateLimiter, never()).recordFailure(any(), any());
	}

	@Test
	void lockedOutCallerIsRefusedBeforeAnyLookupOrComparison() {
		AuthService service = service();
		doThrow(new TooManyLoginAttemptsException(60)).when(rateLimiter).check("known@test.dev", IP);

		assertThatThrownBy(() -> service.authenticate("known@test.dev", "right", IP))
				.isInstanceOf(TooManyLoginAttemptsException.class);

		verify(memberRepository, never()).findByEmail(any());
		verify(passwordEncoder, never()).matches(any(), any());
	}

}
