package org.example.backend.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.backend.model.DoorState;
import org.example.backend.service.DoorStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SocketHandler.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> BUSY_EVENTS = Set.of(
            "ring", "offer", "answer", "connecting", "ice-connected", "call-start", "in_call"
    );
    private static final Set<String> IDLE_EVENTS = Set.of(
            "bye", "hangup", "call-end"
    );

    private final DoorStateStore stateStore;
    private final ObjectMapper om = new ObjectMapper();

    final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    public SocketHandler(DoorStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        sessions.add(session);
        log.info("WS connected: id={} remote={}", session.getId(), session.getRemoteAddress());
    }

    @Override
    public void handleTextMessage(@NonNull WebSocketSession session,
                                  @NonNull TextMessage message) throws IOException {
        parseAndApplyState(message.getPayload());
        broadcast(session, message);
    }

    private void parseAndApplyState(String payload) {
        Map<String, Object> msg;
        try {
            msg = om.readValue(payload, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.debug("WS payload is not JSON (ignored): size={} err={}", payload.length(), e.getOriginalMessage());
            return;
        } catch (Exception e) {
            log.warn("WS payload parse error: {}", e.toString());
            return;
        }

        handleEvent(msg);
        handleIceState(msg.get("iceConnectionState"));
        handleFlags(msg);
    }

    private void handleEvent(Map<String, Object> msg) {
        String ev = asLowerString(msg.get("event"));
        if (ev == null) return;

        if (BUSY_EVENTS.contains(ev)) {
            stateStore.set(DoorState.CONNECTING);
            return;
        }
        if (IDLE_EVENTS.contains(ev)) {
            stateStore.set(DoorState.IDLE);
        }
    }

    private void handleIceState(Object ice) {
        String v = asLowerString(ice);
        if (v == null) return;

        switch (v) {
            case "connected", "completed" -> stateStore.set(DoorState.CONNECTING);
            case "disconnected", "failed", "closed" -> stateStore.set(DoorState.IDLE);
            default -> { /* ignorieren */ }
        }
    }

    private void handleFlags(Map<String, Object> msg) {
        if (Boolean.TRUE.equals(msg.get("ontrack"))) {
            stateStore.set(DoorState.CONNECTING);
        }
    }

    private static String asLowerString(Object o) {
        return (o instanceof String s && !s.isBlank()) ? s.toLowerCase() : null;
    }

    private void broadcast(WebSocketSession sender, TextMessage message) throws IOException {
        log.debug("WS msg from {} -> broadcasting", sender.getId());
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !sender.getId().equals(s.getId())) {
                s.sendMessage(message);
            }
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
        log.info("WS disconnected: id={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable ex) {
        final String id = session.getId();

        log.warn("WS transport error: id={}", id, ex);

        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException closeEx) {
            log.debug("Ignoring close exception after transport error (id={})", id, closeEx);
        } finally {
            sessions.remove(session);
        }
    }
}
