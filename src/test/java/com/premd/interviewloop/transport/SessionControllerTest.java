package com.premd.interviewloop.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST contract coverage for {@code POST /api/sessions} (C2, docs/TASKS.md): the actual
 * JSON field names and status codes the frontend consumes, not just the
 * {@code SessionManager} methods behind them (already covered by
 * {@code TurnOrchestratorIntegrationTest} at the service layer).
 *
 * <p>Session creation never calls a provider (providerId/modelId are stored as plain
 * strings, resolved only when a round actually starts), so this needs no mock LLM
 * provider and no API key — pure HTTP + the real profile/general-practice loaders.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSingleModuleSession_withCompany_returnsExpectedContract() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyProfileId":"google","mode":"single_module",
                                 "moduleType":"dsa","difficultyTarget":"medium"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(greaterThan(0)))
                .andExpect(jsonPath("$.mode").value("SINGLE_MODULE"))
                .andExpect(jsonPath("$.companyProfileId").value("google"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rounds.length()").value(1))
                .andExpect(jsonPath("$.rounds[0].ordinal").value(1))
                .andExpect(jsonPath("$.rounds[0].moduleType").value("dsa"))
                .andExpect(jsonPath("$.rounds[0].status").value("PENDING"))
                .andExpect(jsonPath("$.rounds[0].difficultyTarget").value("medium"));
    }

    @Test
    void createFullLoopSession_withCompany_returnsEveryProfileRound() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyProfileId":"google","mode":"full_loop"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("FULL_LOOP"))
                .andExpect(jsonPath("$.companyProfileId").value("google"))
                // At least one round, and it starts at ordinal 1 — the exact count is the
                // company profile's own business, not this test's to pin and re-break on
                // every profile edit.
                .andExpect(jsonPath("$.rounds[0].ordinal").value(1));
    }

    @Test
    void createSession_withOmittedCompanyProfileId_defaultsToGeneralPractice() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moduleType":"lld","difficultyTarget":"medium"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyProfileId").value("general-practice"))
                .andExpect(jsonPath("$.mode").value("SINGLE_MODULE"))
                .andExpect(jsonPath("$.rounds[0].moduleType").value("lld"));
    }

    @Test
    void createFullLoopSession_forGeneralPractice_returns400() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"full_loop"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSession_afterCreation_returnsTheSameSession() throws Exception {
        String created = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyProfileId":"microsoft","mode":"single_module","moduleType":"hld"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get("/api/sessions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.companyProfileId").value("microsoft"))
                .andExpect(jsonPath("$.rounds[0].moduleType").value("hld"));
    }

    @Test
    void listSessions_includesACreatedSession() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyProfileId":"linkedin","mode":"single_module","moduleType":"behavioral"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.companyProfileId == 'linkedin')]").exists());
    }
}
