package dev.ekin.tokenusage.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepository extends JpaRepository<Member, UUID> {

	Optional<Member> findByEmail(String email);

	List<Member> findByOrgIdOrderByDisplayName(UUID orgId);

	interface TeamMemberCount {
		UUID getTeamId();
		long getMemberCount();
	}

	@Query("""
			select m.teamId as teamId, count(m) as memberCount
			from Member m
			where m.orgId = :orgId and m.teamId is not null
			group by m.teamId
			""")
	List<TeamMemberCount> countByTeam(@Param("orgId") UUID orgId);

}
