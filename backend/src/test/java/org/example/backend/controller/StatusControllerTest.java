package org.example.backend.controller;

import org.example.backend.model.DoorState;
import org.example.backend.service.DoorStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StatusControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DoorStateStore store;

    @BeforeEach
    void reset() {
        store.set(DoorState.IDLE);
    }

    @Test
    void status_returnsIdleByDefault() throws Exception {
        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state", is("idle")));
    }

    @Test
    void status_returnsBusy_whenConnecting() throws Exception {
        store.set(DoorState.CONNECTING);

        mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state", is("busy")));
    }
}
