package dev.ekin.tokenusage.tenant;

import dev.ekin.tokenusage.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseEntity {

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(nullable = false)
	private String name;

	public Project(UUID orgId, String name) {
		this.orgId = orgId;
		this.name = name;
	}

}
