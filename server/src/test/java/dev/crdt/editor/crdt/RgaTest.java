package dev.crdt.editor.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RgaTest {

    @Test
    void localInsertAndDelete() {
        Rga rga = new Rga("a");
        rga.insert(0, "hello");
        rga.insert(5, " world");
        rga.delete(0, 6);
        assertThat(rga.text()).isEqualTo("world");
    }

    @Test
    void concurrentInsertsAtSamePositionConverge() {
        Rga a = new Rga("a");
        Rga b = new Rga("b");
        List<Op> base = a.insert(0, "ac");
        base.forEach(b::apply);

        List<Op> fromA = a.insert(1, "X");
        List<Op> fromB = b.insert(1, "Y");
        fromB.forEach(a::apply);
        fromA.forEach(b::apply);

        assertThat(a.text()).isEqualTo(b.text()).hasSize(4).startsWith("a").endsWith("c");
    }

    @Test
    void concurrentWordsDoNotInterleave() {
        Rga a = new Rga("a");
        Rga b = new Rga("b");
        List<Op> fromA = a.insert(0, "hello");
        List<Op> fromB = b.insert(0, "world");
        fromB.forEach(a::apply);
        fromA.forEach(b::apply);

        assertThat(a.text()).isEqualTo(b.text()).isIn("helloworld", "worldhello");
    }

    @Test
    void deleteConcurrentWithInsertAfterIt() {
        Rga a = new Rga("a");
        Rga b = new Rga("b");
        a.insert(0, "abc").forEach(b::apply);

        List<Op> del = a.delete(1, 1);
        List<Op> ins = b.insert(2, "Z");
        ins.forEach(a::apply);
        del.forEach(b::apply);

        assertThat(a.text()).isEqualTo(b.text()).isEqualTo("aZc");
    }

    @Test
    void outOfOrderDeliveryIsBufferedUntilReady() {
        Rga a = new Rga("a");
        List<Op> ops = new ArrayList<>(a.insert(0, "abc"));
        ops.addAll(a.delete(1, 1));
        Collections.reverse(ops);

        Rga b = new Rga("b");
        ops.forEach(b::apply);

        assertThat(b.text()).isEqualTo("ac");
        assertThat(b.pendingCount()).isZero();
    }

    @Test
    void duplicateOpsAreIgnored() {
        Rga a = new Rga("a");
        List<Op> ops = a.insert(0, "hi");
        Rga b = new Rga("b");
        ops.forEach(op -> assertThat(b.apply(op)).isTrue());
        ops.forEach(op -> assertThat(b.apply(op)).isFalse());
        assertThat(b.text()).isEqualTo("hi");
    }

    @Test
    void replaceTextProducesMinimalDiff() {
        Rga rga = new Rga("a");
        rga.insert(0, "the cat sat");
        List<Op> ops = rga.replaceText("the dog sat");
        assertThat(rga.text()).isEqualTo("the dog sat");
        assertThat(ops).hasSize(6); // 3 deletes + 3 inserts
    }

    @Test
    void snapshotRoundTripKeepsIdsAndClock() {
        Rga a = new Rga("a");
        a.insert(0, "abc");
        a.delete(0, 1);
        Rga restored = Rga.fromNodes("a", a.nodes());

        assertThat(restored.text()).isEqualTo("bc");
        assertThat(restored.nodes()).isEqualTo(a.nodes());
        // New local ops must get a counter above everything already seen.
        Op.Insert next = (Op.Insert) restored.insert(2, "d").getFirst();
        assertThat(next.id().counter()).isEqualTo(4);
    }
}
