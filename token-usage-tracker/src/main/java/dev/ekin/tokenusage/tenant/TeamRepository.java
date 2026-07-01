package dev.ekin.tokenusage.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	List<Team> findByOrgId(UUID orgId);

}
