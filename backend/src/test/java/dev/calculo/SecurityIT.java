package dev.calculo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.calculo.security.LoginRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class SecurityIT extends PostgresTestBase {
    @Autowired MockMvc mvc;
    @Autowired LoginRateLimiter limiter;

    @BeforeEach
    void resetLimiter() {
        limiter.reset();
    }

    private MockHttpServletRequestBuilder login(String password) {
        return post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}");
    }

    private MockHttpSession loginSession() throws Exception {
        MvcResult r = mvc.perform(login(TestAuth.PASSWORD)).andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void portfolioWithoutSessionIs401JsonNotRedirect() throws Exception {
        mvc.perform(get("/api/portfolio"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void openPathsNeedNoSession() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // static frontend is outside /api/**: not an auth failure (404 here because no frontend is bundled in tests)
        int s = mvc.perform(get("/index.html")).andReturn().getResponse().getStatus();
        assertThat(s).isNotIn(401, 403);
    }

    @Test
    void wrongPasswordIs401AndRightPasswordOpensTheSession() throws Exception {
        mvc.perform(login("zle")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(login("")).andExpect(status().isUnauthorized());
        MockHttpSession session = loginSession();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/portfolio").session(session)).andExpect(status().isOk());
    }

    @Test
    void loginChangesTheSessionId() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();
        mvc.perform(login(TestAuth.PASSWORD).session(before)).andExpect(status().isNoContent());
        assertThat(before.getId()).isNotEqualTo(oldId);
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = loginSession();
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/logout").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    private Cookie xsrfCookie() throws Exception {
        return mvc.perform(get("/api/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @Test
    void csrfCookieIsIssuedOnMeEvenWhenUnauthorizedAndReadableByScripts() throws Exception {
        MvcResult r = mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn();
        Cookie xsrf = r.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();
        assertThat(xsrf.getValue()).isNotBlank();
    }

    @Test
    void mutatingRequestNeedsCsrfToken() throws Exception {
        MockHttpSession session = loginSession();
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/prices/refresh").session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(post("/api/prices/refresh").session(session).cookie(xsrf).header("X-XSRF-TOKEN", "inny"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/prices/refresh").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isAccepted());
        mvc.perform(delete("/api/bonds/999999").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sixthAttemptAfterFiveFailuresIs429EvenWithTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) mvc.perform(login("zle")).andExpect(status().isUnauthorized());
        mvc.perform(login("zle")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(login(TestAuth.PASSWORD)).andExpect(status().isTooManyRequests());
    }

    @Test
    void successClearsTheFailureCounter() throws Exception {
        for (int i = 0; i < 4; i++) mvc.perform(login("zle")).andExpect(status().isUnauthorized());
        loginSession();
        for (int i = 0; i < 4; i++) mvc.perform(login("zle")).andExpect(status().isUnauthorized());
    }
}
