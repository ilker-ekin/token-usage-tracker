package dev.ekin.tokenusage.tenant;

import dev.ekin.tokenusage.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "team_id")
	private UUID teamId;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	/** BCrypt hash; null means the member cannot log in. */
	@Column(name = "password_hash")
	private String passwordHash;

	/** Comma-separated, e.g. "ADMIN,MEMBER". */
	@Column(nullable = false)
	private String roles;

	public Member(UUID orgId, UUID teamId, String email, String displayName,
			String passwordHash, String roles) {
		this.orgId = orgId;
		this.teamId = teamId;
		this.email = email;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.roles = roles;
	}

	public List<String> roleList() {
		return Arrays.stream(roles.split(","))
				.map(String::trim)
				.filter(r -> !r.isEmpty())
				.toList();
	}

}