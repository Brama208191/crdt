package dev.crdt.editor.document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Sliding-window limit on documents created per client IP, so a public demo can't be flooded. */
@Component
class CreationRateLimiter {

    static final int MAX_PER_WINDOW = 20;
    static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxPerWindow;
    private final Duration window;

    CreationRateLimiter() {
        this(Clock.systemUTC());
    }

    CreationRateLimiter(Clock clock) {
        this(MAX_PER_WINDOW, WINDOW, clock);
    }

    /** Also used, with a tighter limit, for admin sign-in attempts. */
    CreationRateLimiter(int maxPerWindow, Duration window, Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.window = window;
        this.clock = clock;
    }

    boolean tryAcquire(String clientIp) {
        Instant now = clock.instant();
        Deque<Instant> times = recent.computeIfAbsent(clientIp, ip -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(window))) {
                times.pollFirst();
            }
            if (times.size() >= maxPerWindow) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
