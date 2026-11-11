package dev.ekin.tokenusage.pricing;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 adds org_id to model_pricing. Rows that exist before it stay global,
 * and an org's overrides go away with the org.
 */
class PricingOrgOverrideMigrationTest {

	private static final String ORG = "00000000-0000-0000-0000-0000000000a1";

	@Test
	void existingRowsStayGlobalAndOverridesFollowTheirOrg() throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			flyway(postgres, "9").migrate();

			try (Connection c = connect(postgres); Statement st = c.createStatement()) {
				st.execute("""
						insert into organization (id, name, slug) values ('%s', 'Org', 'org');
						insert into model_pricing (id, model, input_price_per_1k, output_price_per_1k, valid_from)
						values (gen_random_uuid(), 'claude-sonnet-4-6', 0.003, 0.015, '2026-01-01T00:00:00Z');
						""".formatted(ORG));
			}

			flyway(postgres, "latest").migrate();

			try (Connection c = connect(postgres); Statement st = c.createStatement()) {
				try (ResultSet rs = st.executeQuery("select count(*) from model_pricing where org_id is null")) {
					rs.next();
					assertThat(rs.getInt(1)).isEqualTo(1);
				}
				st.execute("""
						insert into model_pricing (id, org_id, model, input_price_per_1k, output_price_per_1k, valid_from)
						values (gen_random_uuid(), '%s', 'claude-sonnet-4-6', 0.006, 0.030, '2026-01-01T00:00:00Z');
						delete from organization where id = '%s';
						""".formatted(ORG, ORG));
				try (ResultSet rs = st.executeQuery("""
						select count(*) filter (where org_id is null), count(*) filter (where org_id is not null)
						from model_pricing
						""")) {
					rs.next();
					assertThat(rs.getInt(1)).as("global rows").isEqualTo(1);
					assertThat(rs.getInt(2)).as("overrides of the deleted org").isZero();
				}
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

}
