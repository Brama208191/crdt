package dev.crdt.editor.document;

import dev.crdt.editor.crdt.Id;
import dev.crdt.editor.crdt.Rga;
import java.util.ArrayList;
import java.util.List;

/** Compact text encoding of CRDT state: one node per line as {@code counter site deleted charCode}. */
final class SnapshotCodec {

    private SnapshotCodec() {}

    static String encode(List<Rga.NodeState> nodes) {
        StringBuilder sb = new StringBuilder(nodes.size() * 16);
        for (Rga.NodeState n : nodes) {
            sb.append(n.id().counter()).append(' ')
                    .append(n.id().site()).append(' ')
                    .append(n.deleted() ? '1' : '0').append(' ')
                    .append((int) n.value().charAt(0)).append('\n');
        }
        return sb.toString();
    }

    static List<Rga.NodeState> decode(String state) {
        List<Rga.NodeState> nodes = new ArrayList<>();
        for (String line : state.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split(" ");
            nodes.add(new Rga.NodeState(
                    new Id(Long.parseLong(f[0]), f[1]),
                    String.valueOf((char) Integer.parseInt(f[3])),
                    f[2].equals("1")));
        }
        return nodes;
    }
}
