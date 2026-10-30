package dev.crdt.editor.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.crdt.editor.TestcontainersConfiguration;
import dev.crdt.editor.crdt.Op;
import dev.crdt.editor.crdt.Rga;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DocumentServiceIntegrationTest {

    @Autowired
    DocumentService service;

    @Test
    void stateSurvivesRestartViaSnapshotPlusLogReplay() {
        UUID id = service.create("crash test");
        Rga alice = new Rga("alice");
        Rga bob = new Rga("bob");

        // Enough ops to cross a snapshot boundary and leave a tail in the log.
        List<Op> ops = new ArrayList<>(alice.insert(0, "x".repeat(DocumentService.SNAPSHOT_EVERY + 10)));
        ops.forEach(bob::apply);
        ops.addAll(bob.delete(0, 5));
        service.applyRemote(id, ops);

        String before = bob.text();
        service.evict(id);

        assertThat(service.state(id).nodes()).hasSize(DocumentService.SNAPSHOT_EVERY + 10);
        assertThat(service.textAt(id, Long.MAX_VALUE)).isEqualTo(before);
        assertThat(service.versions(id)).isNotEmpty();
    }

    @Test
    void duplicateOpsAreNotPersistedTwice() {
        UUID id = service.create("dupes");
        List<Op> ops = new Rga("alice").insert(0, "hey");

        assertThat(service.applyRemote(id, ops)).hasSize(3);
        assertThat(service.applyRemote(id, ops)).isEmpty();
    }

    @Test
    void rejectsEditsPastTheSizeLimitWithoutApplyingThem() {
        UUID id = service.create("too big");
        Rga alice = new Rga("alice");
        List<Op> almostFull = new ArrayList<>(alice.insert(0, "a".repeat(DocumentService.MAX_CHARS - 1)));
        for (int i = 0; i < almostFull.size(); i += DocumentService.MAX_BATCH) {
            service.applyRemote(id, almostFull.subList(i, Math.min(i + DocumentService.MAX_BATCH, almostFull.size())));
        }

        assertThatThrownBy(() -> service.applyRemote(id, alice.insert(0, "bb")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.textAt(id, Long.MAX_VALUE)).hasSize(DocumentService.MAX_CHARS - 1);
    }

    @Test
    void restoreRollsBackWithOrdinaryOps() {
        UUID id = service.create("rollback");
        Rga alice = new Rga("alice");
        service.applyRemote(id, alice.insert(0, "a".repeat(DocumentService.SNAPSHOT_EVERY)));
        long versionSeq = service.versions(id).getFirst().seq();
        service.applyRemote(id, alice.replaceText("something else entirely"));

        List<Op> restoreOps = service.restore(id, versionSeq);
        restoreOps.forEach(alice::apply);

        assertThat(alice.text()).isEqualTo("a".repeat(DocumentService.SNAPSHOT_EVERY));
        service.evict(id);
        assertThat(service.textAt(id, Long.MAX_VALUE)).isEqualTo(alice.text());
    }
}
