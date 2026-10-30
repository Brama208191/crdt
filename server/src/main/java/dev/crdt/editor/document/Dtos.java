package dev.crdt.editor.document;

import dev.crdt.editor.crdt.Id;
import dev.crdt.editor.crdt.Op;
import dev.crdt.editor.crdt.Rga;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Wire formats shared by the REST and STOMP endpoints. */
public final class Dtos {

    private Dtos() {}

    /**
     * Compact op encoding. t: "i" or "d"; (c, s): new char id for inserts, target id for deletes;
     * (rc, rs): id of the char an insert follows (null = start); v: inserted char.
     */
    public record OpDto(String t, long c, String s, Long rc, String rs, String v) {

        static OpDto from(Op op) {
            return switch (op) {
                case Op.Insert ins -> new OpDto("i", ins.id().counter(), ins.id().site(),
                        ins.after() == null ? null : ins.after().counter(),
                        ins.after() == null ? null : ins.after().site(),
                        ins.value());
                case Op.Delete del -> new OpDto("d", del.target().counter(), del.target().site(), null, null, null);
            };
        }

        Op toOp() {
            Id id = new Id(c, s);
            if ("d".equals(t)) {
                return new Op.Delete(id);
            }
            if (v == null || v.length() != 1) {
                throw new IllegalArgumentException("insert must carry exactly one char");
            }
            return new Op.Insert(id, rs == null ? null : new Id(rc, rs), v);
        }
    }

    /** A batch of ops from one site, sent and broadcast as a unit. */
    public record OpBatch(String site, List<OpDto> ops) {}

    public record NodeDto(long c, String s, String v, boolean d) {
        static NodeDto from(Rga.NodeState n) {
            return new NodeDto(n.id().counter(), n.id().site(), n.value(), n.deleted());
        }
    }

    public record DocumentState(UUID id, String title, List<NodeDto> nodes) {}

    public record DocumentSummary(UUID id, String title, OffsetDateTime updatedAt) {}

    public record CreateDocument(String title) {}

    public record Version(long seq, OffsetDateTime createdAt, String preview) {}

    public record VersionContent(long seq, String content) {}

    public record JoinRequest(String name, String color) {}

    public record Participant(String sessionId, String name, String color) {}
}
