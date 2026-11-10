package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.auth.MemberPrincipal;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.TeamRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Every project, team and member of the caller's org, with or without usage.
 * The analytics endpoints only list what has spend in a range; pickers and
 * filters in the dashboard need the full lists. Under /analytics so it
 * shares the dashboard's existing proxy route.
 */
@RestController
public class DirectoryController {

	private final ProjectRepository projectRepository;
	private final TeamRepository teamRepository;
	private final MemberRepository memberRepository;

	public DirectoryController(ProjectRepository projectRepository, TeamRepository teamRepository,
			MemberRepository memberRepository) {
		this.projectRepository = projectRepository;
		this.teamRepository = teamRepository;
		this.memberRepository = memberRepository;
	}

	public record Named(UUID id, String name) {
	}

	public record DirectoryMember(UUID id, String displayName, String email, UUID teamId) {
	}

	public record Directory(List<Named> projects, List<Named> teams, List<DirectoryMember> members) {
	}

	@GetMapping("/analytics/directory")
	@Transactional(readOnly = true)
	public Directory directory(@AuthenticationPrincipal MemberPrincipal principal) {
		UUID orgId = principal.orgId();
		return new Directory(
				projectRepository.findByOrgIdOrderByName(orgId).stream()
						.map(p -> new Named(p.getId(), p.getName())).toList(),
				teamRepository.findByOrgIdOrderByName(orgId).stream()
						.map(t -> new Named(t.getId(), t.getName())).toList(),
				memberRepository.findByOrgIdOrderByDisplayName(orgId).stream()
						.map(m -> new DirectoryMember(m.getId(), m.getDisplayName(), m.getEmail(), m.getTeamId()))
						.toList());
	}

}
