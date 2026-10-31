package dev.crdt.editor.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.crdt.editor.TestcontainersConfiguration;
import dev.crdt.editor.crdt.Op;
import dev.crdt.editor.crdt.Rga;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
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

    @Test
    void clearingHistoryKeepsTheTextAndLetsEditingContinue() {
        UUID id = service.create("clear me");
        Rga alice = new Rga("alice");
        // Separate batches, so there are older versions than the current one.
        service.applyRemote(id, alice.insert(0, "a".repeat(DocumentService.SNAPSHOT_EVERY)));
        service.applyRemote(id, alice.insert(0, "b".repeat(DocumentService.SNAPSHOT_EVERY)));
        service.applyRemote(id, alice.insert(0, "c".repeat(7)));
        long oldVersion = service.versions(id).getLast().seq();
        assertThat(service.versions(id)).hasSizeGreaterThan(1);

        service.clearHistory(id);

        assertThat(service.versions(id)).hasSize(1);
        assertThatThrownBy(() -> service.textAt(id, oldVersion)).isInstanceOf(NoSuchElementException.class);

        // A client that never noticed the clear keeps editing against the same CRDT state.
        service.applyRemote(id, alice.insert(0, "b"));
        service.evict(id);
        assertThat(service.textAt(id, Long.MAX_VALUE)).isEqualTo(alice.text());
        assertThat(service.applyRemote(id, alice.insert(1, "c"))).hasSize(1);
    }

    @Test
    void clearingHistoryErasesTheLettersOfDeletedText() {
        UUID id = service.create("secret");
        Rga alice = new Rga("alice");
        List<Op> typed = alice.insert(0, "public SECRET");
        service.applyRemote(id, typed);
        service.applyRemote(id, alice.delete(6, 7));

        service.clearHistory(id);

        assertThat(service.textAt(id, Long.MAX_VALUE)).isEqualTo("public");
        for (var snapshotState : List.of(service.state(id), stateAfterRestart(id))) {
            assertThat(snapshotState.nodes()).hasSize(13);
            assertThat(snapshotState.nodes().stream().filter(Dtos.NodeDto::d).map(Dtos.NodeDto::v))
                    .containsOnly(DocumentService.ERASED);
        }
        // A client resending its original typing cannot bring the letters back.
        assertThat(service.applyRemote(id, typed)).isEmpty();
        assertThat(service.state(id).nodes().stream().map(Dtos.NodeDto::v).reduce("", String::concat))
                .doesNotContain("SECRET");
    }

    private Dtos.DocumentState stateAfterRestart(UUID id) {
        service.evict(id);
        return service.state(id);
    }

    @Test
    void deletingRemovesTheDocumentAndItsHistory() {
        UUID id = service.create("delete me");
        service.applyRemote(id, new Rga("alice").insert(0, "x".repeat(DocumentService.SNAPSHOT_EVERY)));

        service.delete(id);

        assertThatThrownBy(() -> service.state(id)).isInstanceOf(NoSuchElementException.class);
        assertThat(service.versions(id)).isEmpty();
        assertThat(service.list()).noneMatch(d -> d.id().equals(id));
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NoSuchElementException.class);
    }
}
