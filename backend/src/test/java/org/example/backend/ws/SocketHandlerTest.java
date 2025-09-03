package org.example.backend.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocketHandlerTest {

    private SocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SocketHandler();
    }

    private WebSocketSession mockSession(String id, boolean open) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(open);
        when(s.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        return s;
    }

    @Test
    void afterConnectionEstablished_addsSession() {
        WebSocketSession s1 = mockSession("A", true);
        assertEquals(0, handler.sessions.size());

        handler.afterConnectionEstablished(s1);

        assertEquals(1, handler.sessions.size());
        assertTrue(handler.sessions.contains(s1));
    }

    @Test
    void handleTextMessage_broadcastsToOtherOpenSessions_only() throws Exception {
        WebSocketSession sender = mockSession("A", true);
        WebSocketSession openOther = mockSession("B", true);
        WebSocketSession closedOther = mockSession("C", false);

        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(openOther);
        handler.afterConnectionEstablished(closedOther);

        TextMessage msg = new TextMessage("hello");
        handler.handleTextMessage(sender, msg);

        // Sender selbst darf nichts bekommen
        verify(sender, never()).sendMessage(any());
        // Offene andere Session bekommt die Nachricht
        verify(openOther, times(1)).sendMessage(ArgumentMatchers.same(msg));
        // Geschlossene Session nicht
        verify(closedOther, never()).sendMessage(any());
    }

    @Test
    void handleTextMessage_withSingleSession_noBroadcast_noError() throws Exception {
        WebSocketSession only = mockSession("A", true);
        handler.afterConnectionEstablished(only);

        handler.handleTextMessage(only, new TextMessage("ping"));

        verify(only, never()).sendMessage(any());
    }

    @Test
    void afterConnectionClosed_removesSession() {
        WebSocketSession s1 = mockSession("A", true);
        handler.afterConnectionEstablished(s1);
        assertEquals(1, handler.sessions.size());

        handler.afterConnectionClosed(s1, CloseStatus.NORMAL);

        assertEquals(0, handler.sessions.size());
        assertFalse(handler.sessions.contains(s1));
    }

    @Test
    void handleTransportError_closesAndRemoves() throws Exception {
        WebSocketSession s1 = mockSession("A", true);
        handler.afterConnectionEstablished(s1);
        assertTrue(handler.sessions.contains(s1));

        handler.handleTransportError(s1, new RuntimeException("boom"));

        // Session sollte geschlossen werden (CloseStatus egal)
        verify(s1, times(1)).close();
        // und entfernt sein
        assertFalse(handler.sessions.contains(s1));
    }

    @Test
    void handleTextMessage_doesNotSendBackToSender_evenIfOthersPresent() throws Exception {
        WebSocketSession sender = mockSession("A", true);
        WebSocketSession other = mockSession("B", true);
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(other);

        handler.handleTextMessage(sender, new TextMessage("self-check"));

        verify(sender, never()).sendMessage(any());
        verify(other, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void handleTextMessage_propagatesIOExceptionFromSendMessage() throws Exception {
        WebSocketSession sender = mockSession("A", true);
        WebSocketSession other = mockSession("B", true);
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(other);

        // Simuliere IO-Fehler beim Senden
        doThrow(new IOException("io")).when(other).sendMessage(any(TextMessage.class));

        assertThrows(IOException.class, () ->
                handler.handleTextMessage(sender, new TextMessage("boom")));
    }
}
