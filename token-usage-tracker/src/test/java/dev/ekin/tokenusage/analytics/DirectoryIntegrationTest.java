package dev.ekin.tokenusage.analytics;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import dev.ekin.tokenusage.auth.JwtService;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.Team;
import dev.ekin.tokenusage.tenant.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DirectoryIntegrationTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JwtService jwtService;
	@Autowired
	OrganizationRepository organizationRepository;
	@Autowired
	TeamRepository teamRepository;
	@Autowired
	MemberRepository memberRepository;
	@Autowired
	ProjectRepository projectRepository;

	final List<Object> created = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (int i = created.size() - 1; i >= 0; i--) {
			Object o = created.get(i);
			if (o instanceof Member m) memberRepository.delete(m);
			else if (o instanceof Project p) projectRepository.delete(p);
			else if (o instanceof Team t) teamRepository.delete(t);
			else if (o instanceof Organization org) organizationRepository.delete(org);
		}
	}

	private <T> T keep(T o) {
		created.add(o);
		return o;
	}

	@Test
	void listsTheWholeOrgSortedByNameAndNothingFromOtherOrgs() throws Exception {
		String id = UUID.randomUUID().toString().substring(0, 8);
		Organization org = keep(organizationRepository.save(new Organization("Dir " + id, "dir-" + id)));
		Team platform = keep(teamRepository.save(new Team(org.getId(), "Platform")));
		keep(teamRepository.save(new Team(org.getId(), "Data")));
		Member zoe = keep(memberRepository.save(new Member(org.getId(), platform.getId(), "zoe-" + id + "@d.dev", "Zoe", null, "MEMBER")));
		keep(memberRepository.save(new Member(org.getId(), null, "amy-" + id + "@d.dev", "Amy", null, "MEMBER")));
		keep(projectRepository.save(new Project(org.getId(), "search")));
		keep(projectRepository.save(new Project(org.getId(), "checkout")));
		Organization other = keep(organizationRepository.save(new Organization("Other " + id, "other-" + id)));
		keep(projectRepository.save(new Project(other.getId(), "theirs")));
		keep(teamRepository.save(new Team(other.getId(), "Theirs")));

		mockMvc.perform(get("/analytics/directory").header("Authorization", "Bearer " + jwtService.issueToken(zoe)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.projects.length()").value(2))
				.andExpect(jsonPath("$.projects[0].name").value("checkout"))
				.andExpect(jsonPath("$.projects[1].name").value("search"))
				.andExpect(jsonPath("$.teams.length()").value(2))
				.andExpect(jsonPath("$.teams[0].name").value("Data"))
				.andExpect(jsonPath("$.members.length()").value(2))
				.andExpect(jsonPath("$.members[0].displayName").value("Amy"))
				.andExpect(jsonPath("$.members[0].teamId").doesNotExist())
				.andExpect(jsonPath("$.members[1].teamId").value(platform.getId().toString()));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/analytics/directory")).andExpect(status().isUnauthorized());
	}

}
