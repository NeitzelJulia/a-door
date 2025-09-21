package org.example.backend.controller;

import org.example.backend.service.DoorStateStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatusController {
    private final DoorStateStore store;
    public StatusController(DoorStateStore store) { this.store = store; }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("state", store.get().wire());
    }
}
