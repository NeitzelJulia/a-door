package org.example.backend.service;

import org.example.backend.model.DoorState;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class DoorStateStore {
    private final AtomicReference<DoorState> state = new AtomicReference<>(DoorState.IDLE);
    public DoorState get() { return state.get(); }
    public void set(DoorState s) { state.set(s); }
}
