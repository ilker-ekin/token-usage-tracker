package dev.ekin.tokenusage.seed;

import dev.ekin.tokenusage.pricing.LiteLlmPricingClient;
import dev.ekin.tokenusage.pricing.LiteLlmPricingClient.ModelPrice;
import dev.ekin.tokenusage.pricing.ModelPricing;
import dev.ekin.tokenusage.pricing.ModelPricingRepository;
import dev.ekin.tokenusage.tenant.Member;
import dev.ekin.tokenusage.tenant.MemberRepository;
import dev.ekin.tokenusage.tenant.Organization;
import dev.ekin.tokenusage.tenant.OrganizationRepository;
import dev.ekin.tokenusage.tenant.Project;
import dev.ekin.tokenusage.tenant.ProjectRepository;
import dev.ekin.tokenusage.tenant.Team;
import dev.ekin.tokenusage.tenant.TeamRepository;
import dev.ekin.tokenusage.usage.UsageService;
import dev.ekin.tokenusage.usage.UsageService.IngestCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dev-only fixture data: 1 org, 3 teams, 10 members, 5 projects, 3 pricing
 * rows, 500 events over the last 30 days. Prices come from LiteLLM's catalog
 * when reachable, otherwise from a pinned snapshot of the same catalog.
 * Every member logs in with {@link #SEED_PASSWORD}; the first is ADMIN.
 */
@Component
@Profile("dev")
public class DataSeeder implements CommandLineRunner {

	public static final String SEED_PASSWORD = "seed-password";

	private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

	/** Snapshot of LiteLLM prices (per 1k tokens), 2026-07: fallback when offline. */
	private static final List<ModelPrice> FALLBACK_PRICES = List.of(
			new ModelPrice("claude-sonnet-4-6", new BigDecimal("0.003"), new BigDecimal("0.015")),
			new ModelPrice("claude-opus-4-8", new BigDecimal("0.005"), new BigDecimal("0.025")),
			new ModelPrice("claude-haiku-4-5", new BigDecimal("0.001"), new BigDecimal("0.005")));

	private final OrganizationRepository organizationRepository;
	private final TeamRepository teamRepository;
	private final MemberRepository memberRepository;
	private final ProjectRepository projectRepository;
	private final ModelPricingRepository modelPricingRepository;
	private final LiteLlmPricingClient liteLlmPricingClient;
	private final UsageService usageService;
	private final PasswordEncoder passwordEncoder;

	public DataSeeder(OrganizationRepository organizationRepository, TeamRepository teamRepository,
			MemberRepository memberRepository, ProjectRepository projectRepository,
			ModelPricingRepository modelPricingRepository, LiteLlmPricingClient liteLlmPricingClient,
			UsageService usageService, PasswordEncoder passwordEncoder) {
		this.organizationRepository = organizationRepository;
		this.teamRepository = teamRepository;
		this.memberRepository = memberRepository;
		this.projectRepository = projectRepository;
		this.modelPricingRepository = modelPricingRepository;
		this.liteLlmPricingClient = liteLlmPricingClient;
		this.usageService = usageService;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public void run(String... args) {
		if (organizationRepository.count() > 0) {
			log.info("Seed skipped: data already present");
			return;
		}

		Organization org = organizationRepository.save(new Organization("Acme Corp", "acme"));

		List<Team> teams = teamRepository.saveAll(List.of(
				new Team(org.getId(), "Platform"),
				new Team(org.getId(), "Mobile"),
				new Team(org.getId(), "Data")));

		String passwordHash = passwordEncoder.encode(SEED_PASSWORD);
		List<Member> members = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			Team team = teams.get(i % teams.size());
			String roles = i == 0 ? "ADMIN,MEMBER" : "MEMBER";
			members.add(new Member(org.getId(), team.getId(),
					"member" + (i + 1) + "@acme.dev", "Member " + (i + 1), passwordHash, roles));
		}
		members = memberRepository.saveAll(members);

		List<Project> projects = projectRepository.saveAll(List.of(
				new Project(org.getId(), "checkout"),
				new Project(org.getId(), "search"),
				new Project(org.getId(), "ios-app"),
				new Project(org.getId(), "etl-pipeline"),
				new Project(org.getId(), "internal-tools")));

		Instant pricingValidFrom = Instant.now().minus(Duration.ofDays(365));
		List<String> models = new ArrayList<>();
		for (ModelPrice fallback : FALLBACK_PRICES) {
			ModelPrice price = liteLlmPricingClient.fetchPrice(fallback.model()).orElse(fallback);
			modelPricingRepository.save(new ModelPricing(price.model(),
					price.inputPricePer1k(), price.outputPricePer1k(), pricingValidFrom, null));
			models.add(price.model());
		}

		Random random = new Random(42);
		Instant now = Instant.now();
		for (int i = 0; i < 500; i++) {
			Member member = members.get(random.nextInt(members.size()));
			Project project = projects.get(random.nextInt(projects.size()));
			String model = models.get(random.nextInt(models.size()));
			Instant eventTime = now.minusSeconds(random.nextLong(Duration.ofDays(30).toSeconds()));
			usageService.ingest(member.getId(), new IngestCommand(
					project.getId(), model,
					500 + random.nextInt(20_000), 100 + random.nextInt(8_000),
					random.nextInt(400), random.nextInt(150), eventTime));
		}

		log.info("Seeded 1 org, {} teams, {} members, {} projects, {} pricing rows, 500 events",
				teams.size(), members.size(), projects.size(), models.size());
	}

}
