package dev.ekin.tokenusage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication
public class TokenUsageTrackerApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(TokenUsageTrackerApplication.class);
		// Safe defaults; overridable by application.yml / env, but never absent
		app.setDefaultProperties(Map.of(
				"spring.jpa.open-in-view", "false",
				"spring.jpa.hibernate.ddl-auto", "validate"));
		app.run(args);
	}

}
