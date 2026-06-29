package dev.ekin.tokenusage;

import org.springframework.boot.SpringApplication;

public class TestTokenUsageTrackerApplication {

	public static void main(String[] args) {
		SpringApplication.from(TokenUsageTrackerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
