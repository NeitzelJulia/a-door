package org.example.backend.model;

public enum DoorState {
    IDLE, CONNECTING;

    public String wire() {
        return this == CONNECTING ? "busy" : "idle";
    }
}
