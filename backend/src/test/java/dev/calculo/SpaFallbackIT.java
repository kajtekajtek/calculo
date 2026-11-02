package dev.calculo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.calculo.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SpaFallbackIT extends PostgresTestBase {
    @Autowired MockMvc mvc;
    @Autowired LoginRateLimiter limiter;

    @BeforeEach
    void resetLimiter() {
        limiter.reset();
    }

    @Test
    void clientRoutesGetIndexHtml() throws Exception {
        for (String route : new String[] {"/update", "/rates", "/positions/edo", "/positions/AAA.DE", "/some/deep/route"}) {
            mvc.perform(get(route)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
        }
    }

    @Test
    void rootForwardsToIndexHtml() throws Exception {
        // Spring's welcome-page mapping forwards "/"; MockMvc does not follow forwards.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
    }

    @Test
    void unknownFilesAndApiPathsAreNotIndexHtml() throws Exception {
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        mvc.perform(get("/api/nothing")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/nothing")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginStillWorks() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + TestAuth.PASSWORD + "\"}")).andExpect(status().isNoContent());
    }
}
