package dev.crdt.editor.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Replicated Growable Array: a sequence CRDT for text.
 *
 * <p>Every character gets a unique {@link Id}. An insert names the character it goes after; when
 * several replicas insert after the same character concurrently, the one with the larger id goes
 * first. Deletes only set a tombstone flag, so ids stay addressable forever. Together this makes
 * every replica converge to the same text no matter the order ops are delivered in.
 *
 * <p>Ops whose dependencies have not arrived yet are buffered and retried, so the network does not
 * need to guarantee causal delivery.
 */
public final class Rga {

    /** Serializable view of one node, in document order. */
    public record NodeState(Id id, String value, boolean deleted) {}

    private static final class Node {
        final Id id;
        final String value;
        boolean deleted;
        Node next;

        Node(Id id, String value, boolean deleted) {
            this.id = id;
            this.value = value;
            this.deleted = deleted;
        }
    }

    private final String site;
    private final Node head = new Node(null, "", true);
    private final Map<Id, Node> index = new HashMap<>();
    private final List<Op> pending = new ArrayList<>();
    private long clock;

    public Rga(String site) {
        this.site = site;
    }

    public static Rga fromNodes(String site, List<NodeState> nodes) {
        Rga rga = new Rga(site);
        Node tail = rga.head;
        for (NodeState state : nodes) {
            Node node = new Node(state.id(), state.value(), state.deleted());
            tail.next = node;
            tail = node;
            rga.index.put(node.id, node);
            rga.clock = Math.max(rga.clock, node.id.counter());
        }
        return rga;
    }

    // ---- remote ops ---------------------------------------------------------------------------

    /**
     * Applies an op from any replica.
     *
     * @return true if the op was new to this replica (applied or buffered); false for duplicates
     */
    public boolean apply(Op op) {
        if (isDuplicate(op)) {
            return false;
        }
        if (!isReady(op)) {
            pending.add(op);
            return true;
        }
        integrate(op);
        drainPending();
        return true;
    }

    public int pendingCount() {
        return pending.size();
    }

    private boolean isDuplicate(Op op) {
        return switch (op) {
            case Op.Insert ins -> index.containsKey(ins.id()) || pending.contains(ins);
            case Op.Delete del -> {
                Node target = index.get(del.target());
                yield (target != null && target.deleted) || pending.contains(del);
            }
        };
    }

    private boolean isReady(Op op) {
        return switch (op) {
            case Op.Insert ins -> ins.after() == null || index.containsKey(ins.after());
            case Op.Delete del -> index.containsKey(del.target());
        };
    }

    private void drainPending() {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Iterator<Op> it = pending.iterator(); it.hasNext(); ) {
                Op op = it.next();
                if (isReady(op)) {
                    it.remove();
                    integrate(op);
                    progressed = true;
                }
            }
        }
    }

    private void integrate(Op op) {
        switch (op) {
            case Op.Insert ins -> integrateInsert(ins);
            case Op.Delete del -> index.get(del.target()).deleted = true;
        }
    }

    private void integrateInsert(Op.Insert ins) {
        Node prev = ins.after() == null ? head : index.get(ins.after());
        // Skip concurrent inserts at the same position that have a larger id (and their descendants,
        // which always have even larger Lamport counters).
        while (prev.next != null && prev.next.id.compareTo(ins.id()) > 0) {
            prev = prev.next;
        }
        Node node = new Node(ins.id(), ins.value(), false);
        node.next = prev.next;
        prev.next = node;
        index.put(node.id, node);
        clock = Math.max(clock, ins.id().counter());
    }

    // ---- local edits --------------------------------------------------------------------------

    /** Inserts {@code value} so it starts at visible position {@code pos}. Returns the ops to broadcast. */
    public List<Op> insert(int pos, String value) {
        List<Op> ops = new ArrayList<>(value.length());
        Id after = pos == 0 ? null : visibleNode(pos - 1).id;
        for (int i = 0; i < value.length(); i++) {
            Op.Insert ins = new Op.Insert(new Id(clock + 1, site), after, String.valueOf(value.charAt(i)));
            integrateInsert(ins);
            ops.add(ins);
            after = ins.id();
        }
        return ops;
    }

    /** Deletes {@code length} visible characters starting at {@code pos}. Returns the ops to broadcast. */
    public List<Op> delete(int pos, int length) {
        List<Op> ops = new ArrayList<>(length);
        Node node = length > 0 ? visibleNode(pos) : null;
        for (int i = 0; i < length; i++) {
            while (node.deleted) {
                node = node.next;
            }
            node.deleted = true;
            ops.add(new Op.Delete(node.id));
            node = node.next;
        }
        return ops;
    }

    /** Turns the current text into {@code target} with a minimal prefix/suffix diff. */
    public List<Op> replaceText(String target) {
        String current = text();
        int prefix = 0;
        int max = Math.min(current.length(), target.length());
        while (prefix < max && current.charAt(prefix) == target.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && current.charAt(current.length() - 1 - suffix) == target.charAt(target.length() - 1 - suffix)) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>(delete(prefix, current.length() - prefix - suffix));
        ops.addAll(insert(prefix, target.substring(prefix, target.length() - suffix)));
        return ops;
    }

    private Node visibleNode(int pos) {
        int seen = -1;
        for (Node n = head.next; n != null; n = n.next) {
            if (!n.deleted && ++seen == pos) {
                return n;
            }
        }
        throw new IndexOutOfBoundsException("position " + pos + " out of range");
    }

    // ---- views --------------------------------------------------------------------------------

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Node n = head.next; n != null; n = n.next) {
            if (!n.deleted) {
                sb.append(n.value);
            }
        }
        return sb.toString();
    }

    public List<NodeState> nodes() {
        List<NodeState> out = new ArrayList<>(index.size());
        for (Node n = head.next; n != null; n = n.next) {
            out.add(new NodeState(n.id, n.value, n.deleted));
        }
        return out;
    }
}
