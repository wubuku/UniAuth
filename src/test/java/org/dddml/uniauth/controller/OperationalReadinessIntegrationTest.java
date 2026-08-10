package org.dddml.uniauth.controller;

import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationalReadinessIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicHealthProbesExposeOnlyAggregateStatus() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "jdbc:postgresql"
                                )
                        )
                ));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(
                                        "signing-key"
                                )
                        )
                ));
    }
}
