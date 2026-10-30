package dev.crdt.editor.document;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/docs")
class DocumentController {

    private final DocumentService service;
    private final SimpMessagingTemplate broker;
    private final CreationRateLimiter rateLimiter;

    DocumentController(DocumentService service, SimpMessagingTemplate broker, CreationRateLimiter rateLimiter) {
        this.service = service;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody Dtos.CreateDocument request, HttpServletRequest http) {
        if (!rateLimiter.tryAcquire(http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many new documents; try again later."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", service.create(request.title())));
    }

    @GetMapping
    List<Dtos.DocumentSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    Dtos.DocumentState get(@PathVariable UUID id) {
        return service.state(id);
    }

    @GetMapping("/{id}/versions")
    List<Dtos.Version> versions(@PathVariable UUID id) {
        return service.versions(id);
    }

    @GetMapping("/{id}/versions/{seq}")
    Dtos.VersionContent version(@PathVariable UUID id, @PathVariable long seq) {
        return new Dtos.VersionContent(seq, service.textAt(id, seq));
    }

    @PostMapping("/{id}/versions/{seq}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restore(@PathVariable UUID id, @PathVariable long seq) {
        var ops = service.restore(id, seq);
        if (!ops.isEmpty()) {
            broker.convertAndSend(SyncController.topic(id),
                    new Dtos.OpBatch("server", ops.stream().map(Dtos.OpDto::from).toList()));
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(NoSuchElementException e) {
        return Map.of("error", e.getMessage());
    }
}
