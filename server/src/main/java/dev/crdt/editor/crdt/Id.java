package dev.crdt.editor.crdt;

/**
 * Globally unique, totally ordered identifier for a character.
 * Ordered by Lamport counter first, then by site id as a deterministic tie-breaker.
 */
public record Id(long counter, String site) implements Comparable<Id> {

    @Override
    public int compareTo(Id other) {
        int byCounter = Long.compare(counter, other.counter);
        return byCounter != 0 ? byCounter : site.compareTo(other.site);
    }
}
