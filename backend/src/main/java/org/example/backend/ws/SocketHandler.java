package org.example.backend.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SocketHandler.class);

    List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        sessions.add(session);
        log.info("WS connected: id={} remote={}", session.getId(),
                session.getRemoteAddress());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, @NonNull TextMessage message)
            throws IOException {
        log.debug("WS msg from {} -> broadcasting", session.getId());
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !session.getId().equals(s.getId())) {
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
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS transport error: id={} err={}", session.getId(), exception.toString());
        try { session.close(); } catch (Exception ignored) {}
        sessions.remove(session);
    }

}
