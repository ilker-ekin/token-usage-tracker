package dev.ekin.tokenusage.config;

import dev.ekin.tokenusage.pricing.LiteLlmProperties;
import dev.ekin.tokenusage.usage.IngestProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({IngestProperties.class, LiteLlmProperties.class})
public class AppPropertiesConfig {
}
