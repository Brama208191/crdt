package dev.crdt.editor.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Tracks who is connected to each document and broadcasts the list on every change. */
@Service
class PresenceService {

    private record Entry(UUID docId, Dtos.Participant participant) {}

    private final SimpMessagingTemplate broker;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    PresenceService(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    void join(UUID docId, String sessionId, Dtos.JoinRequest request) {
        String name = request.name() == null || request.name().isBlank() ? "Anonymous" : request.name().strip();
        name = name.length() > 30 ? name.substring(0, 30) : name;
        sessions.put(sessionId, new Entry(docId, new Dtos.Participant(sessionId, name, request.color())));
        broadcast(docId);
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        Entry removed = sessions.remove(event.getSessionId());
        if (removed != null) {
            broadcast(removed.docId());
        }
    }

    private void broadcast(UUID docId) {
        List<Dtos.Participant> participants = sessions.values().stream()
                .filter(e -> e.docId().equals(docId))
                .map(Entry::participant)
                .toList();
        broker.convertAndSend(SyncController.topic(docId) + "/presence", participants);
    }
}
