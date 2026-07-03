package dev.ekin.tokenusage.security;

import dev.ekin.tokenusage.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityFilterChainTest {

    @Autowired
    MockMvc mockMvc;

    /**
     * Health must be reachable without any credentials.
     * Load balancers and Railway's health probe hit this endpoint — if it
     * suddenly requires a token the app is marked unhealthy and restarted.
     */
    @Test
    void healthEndpoint_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * Metrics expose JVM memory, thread counts, and HTTP request rates —
     * enough to fingerprint the infrastructure. Must never be public.
     */
    @Test
    void metricsEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The ingestion endpoint is the core write path.
     * An unauthenticated POST here would let anyone inject arbitrary usage data.
     */
    @Test
    void ingestEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/usage"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The login endpoint must be public — it is how clients get a token in
     * the first place. An empty body yields 400 (validation), proving the
     * request reached the controller instead of being rejected with 401.
     */
    @Test
    void tokenEndpoint_isPublic() throws Exception {
        mockMvc.perform(post("/auth/token").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies that .anyRequest().authenticated() is a true catch-all —
     * not just a list of known routes. An unknown path must also return 401,
     * not 404, so new endpoints are protected by default even before they
     * are explicitly listed in the security config.
     */
    @Test
    void unknownEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

}
