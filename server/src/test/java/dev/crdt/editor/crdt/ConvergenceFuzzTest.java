package dev.crdt.editor.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Simulates several replicas editing concurrently over an unreliable network that reorders and
 * duplicates messages, then checks every replica ends up with identical text.
 */
class ConvergenceFuzzTest {

    private static final int REPLICAS = 5;
    private static final int ROUNDS = 2_000;
    private static final String ALPHABET = "abcdefghij \n";

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 42, 1337})
    void replicasConvergeUnderReorderingAndDuplication(long seed) {
        Random random = new Random(seed);
        List<Rga> replicas = new ArrayList<>();
        List<List<Op>> inboxes = new ArrayList<>();
        for (int i = 0; i < REPLICAS; i++) {
            replicas.add(new Rga("site-" + i));
            inboxes.add(new ArrayList<>());
        }

        for (int round = 0; round < ROUNDS; round++) {
            int r = random.nextInt(REPLICAS);
            Rga replica = replicas.get(r);

            if (random.nextInt(3) == 0) {
                deliverSome(random, replica, inboxes.get(r));
                continue;
            }

            List<Op> ops = randomEdit(random, replica);
            for (int other = 0; other < REPLICAS; other++) {
                if (other == r) continue;
                for (Op op : ops) {
                    inboxes.get(other).add(op);
                    if (random.nextInt(10) == 0) {
                        inboxes.get(other).add(op); // duplicate delivery
                    }
                }
            }
        }

        for (int r = 0; r < REPLICAS; r++) {
            List<Op> inbox = inboxes.get(r);
            while (!inbox.isEmpty()) {
                replicas.get(r).apply(inbox.remove(random.nextInt(inbox.size())));
            }
        }

        String expected = replicas.getFirst().text();
        assertThat(expected).isNotEmpty();
        for (Rga replica : replicas) {
            assertThat(replica.text()).isEqualTo(expected);
            assertThat(replica.nodes()).isEqualTo(replicas.getFirst().nodes());
            assertThat(replica.pendingCount()).isZero();
        }
    }

    private static void deliverSome(Random random, Rga replica, List<Op> inbox) {
        int count = inbox.isEmpty() ? 0 : random.nextInt(inbox.size()) + 1;
        for (int i = 0; i < count; i++) {
            replica.apply(inbox.remove(random.nextInt(inbox.size())));
        }
    }

    private static List<Op> randomEdit(Random random, Rga replica) {
        int length = replica.text().length();
        if (length > 0 && random.nextInt(3) == 0) {
            int pos = random.nextInt(length);
            return replica.delete(pos, 1 + random.nextInt(Math.min(3, length - pos)));
        }
        StringBuilder word = new StringBuilder();
        for (int i = random.nextInt(4); i >= 0; i--) {
            word.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return replica.insert(random.nextInt(length + 1), word.toString());
    }
}
