package dev.ekin.tokenusage.auth;

import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final LoginRateLimiter loginRateLimiter;

	/**
	 * Compared against when there is no real hash, so an unknown email costs
	 * the same hashing work as a wrong password and response time does not
	 * reveal which emails have an account.
	 */
	private final String dummyPasswordHash;

	public AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder,
			JwtService jwtService, LoginRateLimiter loginRateLimiter) {
		this.memberRepository = memberRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.loginRateLimiter = loginRateLimiter;
		this.dummyPasswordHash = passwordEncoder.encode("no-member-has-this-password");
	}

	/**
	 * @param clientIp address the request came from, for the per-address limit
	 * @throws TooManyLoginAttemptsException when the email or the address is
	 *         locked out; checked before the password, so even a correct one
	 *         is refused during a lockout
	 * @throws BadCredentialsException for an unknown email, a member without a
	 *         password, or a wrong password, indistinguishably
	 */
	@Transactional(readOnly = true)
	public String authenticate(String email, String password, String clientIp) {
		loginRateLimiter.check(email, clientIp);

		Member member = memberRepository.findByEmail(email).orElse(null);
		String hash = member != null && member.getPasswordHash() != null
				? member.getPasswordHash() : dummyPasswordHash;
		boolean passwordMatches = passwordEncoder.matches(password, hash);

		if (member == null || member.getPasswordHash() == null || !passwordMatches) {
			loginRateLimiter.recordFailure(email, clientIp);
			throw new BadCredentialsException("Invalid credentials");
		}
		loginRateLimiter.recordSuccess(email);
		return jwtService.issueToken(member);
	}

}
