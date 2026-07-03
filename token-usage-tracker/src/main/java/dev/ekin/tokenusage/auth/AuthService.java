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

	public AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder,
			JwtService jwtService) {
		this.memberRepository = memberRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional(readOnly = true)
	public String authenticate(String email, String password) {
		Member member = memberRepository.findByEmail(email)
				.orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
		if (member.getPasswordHash() == null
				|| !passwordEncoder.matches(password, member.getPasswordHash())) {
			throw new BadCredentialsException("Invalid credentials");
		}
		return jwtService.issueToken(member);
	}

}