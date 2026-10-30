package dev.crdt.editor.document;

import dev.crdt.editor.crdt.Op;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * STOMP endpoints. Clients publish op batches to {@code /app/doc/{id}/ops}; the server applies
 * them to its replica, persists the new ones and fans them out on {@code /topic/doc/{id}}.
 */
@Controller
class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final DocumentService documents;
    private final PresenceService presence;
    private final SimpMessagingTemplate broker;

    SyncController(DocumentService documents, PresenceService presence, SimpMessagingTemplate broker) {
        this.documents = documents;
        this.presence = presence;
        this.broker = broker;
    }

    static String topic(UUID docId) {
        return "/topic/doc/" + docId;
    }

    /**
     * Always broadcasts a reply, even when nothing was new or the batch was rejected: the sender
     * treats its own site's echo as the acknowledgement that lets it drop the batch from its retry
     * queue. Other clients ignore empty batches.
     */
    @MessageMapping("/doc/{id}/ops")
    void ops(@DestinationVariable UUID id, @Payload Dtos.OpBatch batch) {
        List<Op> fresh = List.of();
        try {
            fresh = documents.applyRemote(id, batch.ops().stream().map(Dtos.OpDto::toOp).toList());
        } catch (IllegalArgumentException | NoSuchElementException e) {
            log.warn("Rejected batch for doc {}: {}", id, e.getMessage());
        }
        broker.convertAndSend(topic(id), new Dtos.OpBatch(batch.site(), fresh.stream().map(Dtos.OpDto::from).toList()));
    }

    @MessageMapping("/doc/{id}/join")
    void join(@DestinationVariable UUID id, @Payload Dtos.JoinRequest request,
              @Header("simpSessionId") String sessionId) {
        presence.join(id, sessionId, request);
    }
}
