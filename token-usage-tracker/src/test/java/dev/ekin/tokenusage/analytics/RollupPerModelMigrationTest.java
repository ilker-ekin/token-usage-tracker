package dev.ekin.tokenusage.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V9 adds model to the rollup key and rebuilds the table from usage_event.
 * Runs Flyway to V8, books events (one on a Sunday, one a second before
 * midnight UTC) plus a rollup row that no event backs, then applies V9.
 */
class RollupPerModelMigrationTest {

	private static final String ORG = "00000000-0000-0000-0000-0000000000a1";
	private static final String MEMBER = "00000000-0000-0000-0000-0000000000c1";
	private static final String PROJECT = "00000000-0000-0000-0000-0000000000d1";

	@Test
	void rollupIsRebuiltPerModelFromTheEvents() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			flyway(postgres, "8").migrate();

			try (Connection c = connect(postgres); Statement st = c.createStatement()) {
				st.execute("""
						insert into organization (id, name, slug) values ('%1$s', 'Org', 'org');
						insert into member (id, org_id, team_id, email, display_name) values ('%2$s', '%1$s', null, 'm@test.dev', 'M');
						insert into project (id, org_id, name) values ('%3$s', '%1$s', 'P');
						""".formatted(ORG, MEMBER, PROJECT));
				// 2026-06-14 is a Sunday: its week starts Monday 2026-06-08
				event(st, "opus", "2026-06-14T12:00:00Z", 100, 10, "1.000000", 5);
				event(st, "opus", "2026-06-15T23:59:59Z", 200, 20, "2.000000", null);
				event(st, "haiku", "2026-06-15T08:00:00Z", 300, 30, "0.250000", 1);
				event(st, "haiku", "2026-07-01T00:00:00Z", 400, 40, "0.500000", 0);
				// A rollup row no event backs: the rebuild drops it
				st.execute("""
						insert into rollup (id, org_id, team_id, member_id, project_id, granularity, period_start,
						        total_input_tokens, total_cost_usd)
						values (gen_random_uuid(), '%s', null, '%s', '%s', 'DAY', '2026-06-20', 999, 99)
						""".formatted(ORG, MEMBER, PROJECT));
			}

			flyway(postgres, "latest").migrate();

			try (Connection c = connect(postgres); Statement st = c.createStatement()) {
				List<String> rows = new ArrayList<>();
				try (ResultSet rs = st.executeQuery("""
						select granularity, period_start, model, total_input_tokens, total_output_tokens,
						       total_cost_usd, total_lines_added
						from rollup order by granularity, period_start, model
						""")) {
					while (rs.next()) {
						rows.add("%s %s %s in=%d out=%d cost=%s lines=%d".formatted(rs.getString(1), rs.getDate(2),
								rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getBigDecimal(6).toPlainString(),
								rs.getLong(7)));
					}
				}
				assertThat(rows).containsExactly(
						"DAY 2026-06-14 opus in=100 out=10 cost=1.000000 lines=5",
						"DAY 2026-06-15 haiku in=300 out=30 cost=0.250000 lines=1",
						"DAY 2026-06-15 opus in=200 out=20 cost=2.000000 lines=0",
						"DAY 2026-07-01 haiku in=400 out=40 cost=0.500000 lines=0",
						"MONTH 2026-06-01 haiku in=300 out=30 cost=0.250000 lines=1",
						"MONTH 2026-06-01 opus in=300 out=30 cost=3.000000 lines=5",
						"MONTH 2026-07-01 haiku in=400 out=40 cost=0.500000 lines=0",
						"WEEK 2026-06-08 opus in=100 out=10 cost=1.000000 lines=5",
						"WEEK 2026-06-15 haiku in=300 out=30 cost=0.250000 lines=1",
						"WEEK 2026-06-15 opus in=200 out=20 cost=2.000000 lines=0",
						"WEEK 2026-06-29 haiku in=400 out=40 cost=0.500000 lines=0");

				// model is now part of the key and required
				String duplicate = """
						insert into rollup (id, org_id, team_id, member_id, project_id, model, granularity, period_start)
						values (gen_random_uuid(), '%s', null, '%s', '%s', 'opus', 'DAY', '2026-06-14')
						""".formatted(ORG, MEMBER, PROJECT);
				assertThatThrownBy(() -> st.execute(duplicate)).hasMessageContaining("uq_rollup_key");
				String noModel = """
						insert into rollup (id, org_id, team_id, member_id, project_id, granularity, period_start)
						values (gen_random_uuid(), '%s', null, '%s', '%s', 'DAY', '2026-06-21')
						""".formatted(ORG, MEMBER, PROJECT);
				assertThatThrownBy(() -> st.execute(noModel)).hasMessageContaining("model");
			}
		}
	}

	private static void event(Statement st, String model, String time, long in, long out, String cost,
			Integer linesAdded) throws Exception {
		st.execute("""
				insert into usage_event (id, org_id, team_id, member_id, project_id, model, input_tokens,
				        output_tokens, lines_added, cost_usd, event_time)
				values (gen_random_uuid(), '%s', null, '%s', '%s', '%s', %d, %d, %s, %s, '%s')
				""".formatted(ORG, MEMBER, PROJECT, model, in, out, linesAdded == null ? "null" : linesAdded, cost, time));
	}

	private static Flyway flyway(PostgreSQLContainer postgres, String target) {
		return Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target(target)
				.load();
	}

	private static Connection connect(PostgreSQLContainer postgres) throws Exception {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

}
