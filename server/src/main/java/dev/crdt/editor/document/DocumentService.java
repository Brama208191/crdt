package dev.crdt.editor.document;

import dev.crdt.editor.crdt.Op;
import dev.crdt.editor.crdt.Rga;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The server is itself a CRDT replica. It keeps a live {@link Rga} per open document, appends every
 * new op to the log, and snapshots the full state every {@value #SNAPSHOT_EVERY} ops so reloads
 * and version previews only replay a short tail of the log.
 */
@Service
public class DocumentService {

    static final int SNAPSHOT_EVERY = 50;
    /** Must match MAX_CHARS in the client's EditorPage. */
    static final int MAX_CHARS = 100_000;
    static final int MAX_BATCH = 10_000;
    private static final String SERVER_SITE = "server";

    /** What a deleted character's letter becomes once history is cleared. */
    static final String ERASED = " ";

    private static final class LiveDoc {
        Rga rga;
        long seq;
        long lastSnapshotSeq;

        LiveDoc(Rga rga, long seq, long lastSnapshotSeq) {
            this.rga = rga;
            this.seq = seq;
            this.lastSnapshotSeq = lastSnapshotSeq;
        }
    }

    private final DocumentRepository repo;
    private final TransactionTemplate tx;
    private final Map<UUID, LiveDoc> live = new ConcurrentHashMap<>();

    public DocumentService(DocumentRepository repo, TransactionTemplate tx) {
        this.repo = repo;
        this.tx = tx;
    }

    public UUID create(String title) {
        return create(title, null);
    }

    /** {@code ownerKeyHash} may be null: then only an admin can delete the document. */
    public UUID create(String title, String ownerKeyHash) {
        UUID id = UUID.randomUUID();
        String clean = title == null || title.isBlank() ? "Untitled" : title.strip();
        repo.create(id, clean.length() > 100 ? clean.substring(0, 100) : clean, ownerKeyHash);
        return id;
    }

    /** Removes the document, its op log and its snapshots. */
    public void delete(UUID id) {
        LiveDoc doc = live.remove(id);
        Object lock = doc == null ? new Object() : doc;
        synchronized (lock) {
            if (!repo.delete(id)) {
                throw new NoSuchElementException("document " + id);
            }
        }
    }

    /**
     * Forgets every saved version but keeps the current text. Deleted characters stay as tombstones,
     * because concurrent ops may still refer to their ids, but their letters are overwritten, so
     * erased text can no longer be read from the state, the log or a snapshot. Connected clients are
     * unaffected: every id and the visible text stay the same.
     */
    public void clearHistory(UUID id) {
        repo.findTitle(id).orElseThrow(() -> new NoSuchElementException("document " + id));
        LiveDoc doc = load(id);
        synchronized (doc) {
            if (doc.seq == 0) {
                return;
            }
            if (doc.rga.pendingCount() > 0) {
                // Those ops live only in the log being dropped; they'd be lost.
                throw new IllegalStateException("edits are still arriving; try again in a moment");
            }
            List<Rga.NodeState> erased = doc.rga.nodes().stream()
                    .map(n -> n.deleted() ? new Rga.NodeState(n.id(), ERASED, true) : n)
                    .toList();
            Rga compacted = Rga.fromNodes(SERVER_SITE, erased);
            tx.executeWithoutResult(status ->
                    repo.compact(id, doc.seq, SnapshotCodec.encode(erased), compacted.text()));
            doc.rga = compacted;
            doc.lastSnapshotSeq = doc.seq;
        }
    }

    public List<Dtos.DocumentSummary> list() {
        return repo.list();
    }

    public Dtos.DocumentState state(UUID id) {
        String title = repo.findTitle(id).orElseThrow(() -> new NoSuchElementException("document " + id));
        LiveDoc doc = load(id);
        synchronized (doc) {
            return new Dtos.DocumentState(id, title, doc.rga.nodes().stream().map(Dtos.NodeDto::from).toList());
        }
    }

    /** Applies ops from a client and returns the ones that were new, which should be broadcast. */
    public List<Op> applyRemote(UUID id, List<Op> ops) {
        if (ops.size() > MAX_BATCH) {
            throw new IllegalArgumentException("batch of " + ops.size() + " ops exceeds " + MAX_BATCH);
        }
        LiveDoc doc = load(id);
        synchronized (doc) {
            long inserts = ops.stream().filter(Op.Insert.class::isInstance).count();
            if (doc.rga.text().length() + inserts > MAX_CHARS) {
                throw new IllegalArgumentException("document would exceed " + MAX_CHARS + " characters");
            }
            List<Op> fresh = new ArrayList<>();
            for (Op op : ops) {
                if (doc.rga.apply(op)) {
                    fresh.add(op);
                }
            }
            persist(id, doc, fresh);
            return fresh;
        }
    }

    public List<Dtos.Version> versions(UUID id) {
        return repo.versions(id);
    }

    /** Text of the document as it was right after op {@code seq}. */
    public String textAt(UUID id, long seq) {
        if (seq < repo.historyStart(id)) {
            throw new NoSuchElementException("version " + seq + " was cleared");
        }
        return rebuild(id, seq).text();
    }

    /**
     * Rolls the document back to version {@code seq}. Rather than rewriting history, this emits
     * ordinary ops that turn the current text into the old one, so connected clients converge with
     * no special handling and the restore itself shows up in history.
     */
    public List<Op> restore(UUID id, long seq) {
        String target = textAt(id, seq);
        LiveDoc doc = load(id);
        synchronized (doc) {
            List<Op> ops = doc.rga.replaceText(target);
            persist(id, doc, ops);
            return ops;
        }
    }

    /** Drops the in-memory copy, as if the server had restarted. Visible for tests. */
    void evict(UUID id) {
        live.remove(id);
    }

    private void persist(UUID id, LiveDoc doc, List<Op> ops) {
        if (ops.isEmpty()) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                repo.appendOps(id, doc.seq + 1, ops);
                if (doc.seq + ops.size() - doc.lastSnapshotSeq >= SNAPSHOT_EVERY) {
                    repo.saveSnapshot(id, doc.seq + ops.size(), SnapshotCodec.encode(doc.rga.nodes()), doc.rga.text());
                    doc.lastSnapshotSeq = doc.seq + ops.size();
                }
            });
            doc.seq += ops.size();
        } catch (RuntimeException e) {
            // Memory is now ahead of the database; reload from the durable log next time.
            live.remove(id);
            throw e;
        }
    }

    private LiveDoc load(UUID id) {
        return live.computeIfAbsent(id, key -> {
            long maxSeq = repo.maxSeq(key);
            long snapshotSeq = repo.latestSnapshotAtOrBefore(key, maxSeq).map(DocumentRepository.Snapshot::seq).orElse(0L);
            return new LiveDoc(rebuild(key, maxSeq), maxSeq, snapshotSeq);
        });
    }

    private Rga rebuild(UUID id, long upToSeq) {
        var snapshot = repo.latestSnapshotAtOrBefore(id, upToSeq);
        Rga rga = snapshot.map(s -> Rga.fromNodes(SERVER_SITE, SnapshotCodec.decode(s.state())))
                .orElseGet(() -> new Rga(SERVER_SITE));
        long fromSeq = snapshot.map(DocumentRepository.Snapshot::seq).orElse(0L);
        repo.opsBetween(id, fromSeq, upToSeq).forEach(rga::apply);
        return rga;
    }
}
