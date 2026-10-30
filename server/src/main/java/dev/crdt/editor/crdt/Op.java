package dev.crdt.editor.crdt;

/** A replicated operation. Ops are commutative and idempotent once causally ready. */
public sealed interface Op {

    /** Insert {@code value} (one UTF-16 code unit) immediately after {@code after}; null means document start. */
    record Insert(Id id, Id after, String value) implements Op {}

    /** Tombstone the character with id {@code target}. */
    record Delete(Id target) implements Op {}
}
