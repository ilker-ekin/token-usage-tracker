package dev.ekin.tokenusage.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8 moves existing rows out of daily_rollup, weekly_rollup and
 * monthly_rollup into the single rollup table. Runs Flyway up to V7 on an
 * empty database, fills the three old tables, applies the rest and compares.
 */
class RollupTableMigrationTest {

	private static final String ORG = "00000000-0000-0000-0000-0000000000a1";
	private static final String TEAM = "00000000-0000-0000-0000-0000000000b1";
	private static final String MEMBER = "00000000-0000-0000-0000-0000000000c1";
	private static final String SOLO = "00000000-0000-0000-0000-0000000000c2";
	private static final String PROJECT = "00000000-0000-0000-0000-0000000000d1";

	@Test
	void existingRollupRowsAreCarriedOverWithTheirTotals() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			flyway(postgres, "7").migrate();

			try (Connection c = connect(postgres); Statement st = c.createStatement()) {
				st.execute("""
						insert into organization (id, name, slug) values ('%1$s', 'Org', 'org');
						insert into team (id, org_id, name) values ('%2$s', '%1$s', 'Team');
						insert into member (id, org_id, team_id, email, display_name) values
						    ('%3$s', '%1$s', '%2$s', 'm@test.dev', 'M'),
						    ('%4$s', '%1$s', null, 's@test.dev', 'S');
						insert into project (id, org_id, name) values ('%5$s', '%1$s', 'P');
						""".formatted(ORG, TEAM, MEMBER, SOLO, PROJECT));
				// (table, date column, date, member, team, input, output, read, write, cost, added, removed)
				insertOld(st, "daily_rollup", "rollup_date", "2026-06-15", MEMBER, TEAM, 100, 50, 10, 4, "1.500000", 7, 3);
				insertOld(st, "daily_rollup", "rollup_date", "2026-06-16", MEMBER, TEAM, 200, 60, 20, 8, "2.250000", 1, 0);
				insertOld(st, "daily_rollup", "rollup_date", "2026-06-15", SOLO, null, 300, 70, 0, 0, "0.000001", 0, 0);
				insertOld(st, "weekly_rollup", "week_start", "2026-06-15", MEMBER, TEAM, 300, 110, 30, 12, "3.750000", 8, 3);
				insertOld(st, "weekly_rollup", "week_start", "2026-06-15", SOLO, null, 300, 70, 0, 0, "0.000001", 0, 0);
				insertOld(st, "monthly_rollup", "month_start", "2026-06-01", MEMBER, TEAM, 900, 400, 90, 36, "12.125000", 20, 9);
			}

			// Stop at V8: V9 rebuilds rollup from usage_event, which this test leaves empty
			flyway(postgres, "8").migrate();

			try (Connection c = connect(postgres); Statement st = c.createStatement()) {
				Map<String, String> byGranularity = new LinkedHashMap<>();
				try (ResultSet rs = st.executeQuery("""
						select granularity, count(*) as n, sum(total_input_tokens) as input,
						       sum(total_output_tokens) as output, sum(total_cache_read_tokens) as cache_read,
						       sum(total_cache_creation_tokens) as cache_creation, sum(total_cost_usd) as cost,
						       sum(total_lines_added) as added, sum(total_lines_removed) as removed
						from rollup group by granularity order by granularity
						""")) {
					while (rs.next()) {
						byGranularity.put(rs.getString("granularity"), "%d rows in=%d out=%d read=%d write=%d cost=%s +%d -%d"
								.formatted(rs.getLong("n"), rs.getLong("input"), rs.getLong("output"),
										rs.getLong("cache_read"), rs.getLong("cache_creation"),
										rs.getBigDecimal("cost").toPlainString(), rs.getLong("added"), rs.getLong("removed")));
					}
				}
				assertThat(byGranularity).containsExactly(
						Map.entry("DAY", "3 rows in=600 out=180 read=30 write=12 cost=3.750001 +8 -3"),
						Map.entry("MONTH", "1 rows in=900 out=400 read=90 write=36 cost=12.125000 +20 -9"),
						Map.entry("WEEK", "2 rows in=600 out=180 read=30 write=12 cost=3.750001 +8 -3"));

				// One row survives with its key and period intact, team-less member included
				try (ResultSet rs = st.executeQuery("""
						select period_start, team_id, total_cost_usd from rollup
						where granularity = 'WEEK' and member_id = '%s'
						""".formatted(SOLO))) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getDate("period_start")).hasToString("2026-06-15");
					assertThat(rs.getObject("team_id")).isNull();
					assertThat(rs.getBigDecimal("total_cost_usd")).isEqualByComparingTo(new BigDecimal("0.000001"));
				}

				// The old tables are gone
				try (ResultSet rs = st.executeQuery("""
						select count(*) from information_schema.tables
						where table_name in ('daily_rollup', 'weekly_rollup', 'monthly_rollup')
						""")) {
					rs.next();
					assertThat(rs.getInt(1)).isZero();
				}

				// A second team-less row for an existing key is refused: NULLS NOT DISTINCT carried over
				String duplicate = """
						insert into rollup (id, org_id, team_id, member_id, project_id, granularity, period_start)
						values (gen_random_uuid(), '%s', null, '%s', '%s', 'WEEK', '2026-06-15')
						""".formatted(ORG, SOLO, PROJECT);
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(duplicate))
						.hasMessageContaining("uq_rollup_key");
			}
		}
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

	private static void insertOld(Statement st, String table, String dateColumn, String date, String member,
			String team, long input, long output, long cacheRead, long cacheCreation, String cost,
			long added, long removed) throws Exception {
		st.execute("""
				insert into %s (id, org_id, team_id, member_id, project_id, %s, total_input_tokens,
				        total_output_tokens, total_cache_read_tokens, total_cache_creation_tokens,
				        total_cost_usd, total_lines_added, total_lines_removed)
				values (gen_random_uuid(), '%s', %s, '%s', '%s', '%s', %d, %d, %d, %d, %s, %d, %d)
				""".formatted(table, dateColumn, ORG, team == null ? "null" : "'" + team + "'", member, PROJECT,
				date, input, output, cacheRead, cacheCreation, cost, added, removed));
	}

}
