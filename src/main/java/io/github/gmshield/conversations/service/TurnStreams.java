package io.github.gmshield.conversations.service;

import io.github.gmshield.shared.service.OwnerContext;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The server-sent event streams of turns (Story 2.4). The database is the stream's truth: each stream runs on a virtual
 * thread of its own as the turn's owner, sends the stored events after the last one sent (from {@code Last-Event-ID},
 * 0 when absent), each with its sequence as {@code id:}, then waits for more. A store wakes it after its commit
 * ({@link #wake}); a poll every {@code gmshield.conversations.poll} (1 s) is the backstop, so a second backend instance
 * needs nothing more. A comment line ({@code : keep-alive}) goes out every {@code gmshield.conversations.keep-alive}
 * (15 s) without an event. The stream closes after {@code done} or {@code error}, after
 * {@code gmshield.conversations.stream-timeout} (30 min), or when the client leaves; none of these touches the job.
 * Lines carry ids and counts only.
 */
@Service
public class TurnStreams implements DisposableBean {

    static final String KEEP_ALIVE = "keep-alive";
    private static final Logger log = LoggerFactory.getLogger(TurnStreams.class);

    private final Conversations conversations;
    private final Duration keepAlive;
    private final Duration poll;
    private final Duration timeout;
    private final Map<UUID, Set<Stream>> open = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    TurnStreams(Conversations conversations,
                @Value("${gmshield.conversations.keep-alive:15s}") Duration keepAlive,
                @Value("${gmshield.conversations.poll:1s}") Duration poll,
                @Value("${gmshield.conversations.stream-timeout:30m}") Duration timeout) {
        if (keepAlive.isNegative() || keepAlive.isZero() || poll.isNegative() || poll.isZero()
                || timeout.compareTo(keepAlive) <= 0) {
            throw new IllegalStateException("setting.invalid: gmshield.conversations");
        }
        this.conversations = conversations;
        this.keepAlive = keepAlive;
        this.poll = poll;
        this.timeout = timeout;
    }

    /**
     * A stream of the owner's turn (checked by the caller) from after {@code after}; it starts sending at once on a
     * thread of its own.
     */
    public SseEmitter open(UUID owner, UUID turnId, long after) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        Stream stream = new Stream(owner, turnId, after, emitter);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(() -> {
            stream.close();
            emitter.complete();
        });
        emitter.onError(failure -> stream.close());
        // One atomic step with the removal of a closing stream's set, so the new stream never lands in an orphan.
        open.compute(turnId, (id, current) -> {
            Set<Stream> streams = current == null ? ConcurrentHashMap.newKeySet() : current;
            streams.add(stream);
            return streams;
        });
        Thread.ofVirtual().name("turn-stream-" + turnId).start(stream);
        return emitter;
    }

    /** Wakes the streams of a turn: an event was stored. */
    public void wake(UUID turnId) {
        Set<Stream> streams = open.get(turnId);
        if (streams != null) {
            streams.forEach(Stream::wake);
        }
    }

    /** The number of streams open now (tests). */
    int openStreams() {
        return open.values().stream().mapToInt(Set::size).sum();
    }

    @Override
    public void destroy() {
        stopping.set(true);
        open.values().forEach(streams -> streams.forEach(Stream::wake));
    }

    /** One client's stream of one turn. */
    private final class Stream implements Runnable {

        private final UUID owner;
        private final UUID turnId;
        private final SseEmitter emitter;
        private final Semaphore woken = new Semaphore(0);
        private final AtomicBoolean closed = new AtomicBoolean();
        private long last;

        Stream(UUID owner, UUID turnId, long after, SseEmitter emitter) {
            this.owner = owner;
            this.turnId = turnId;
            this.last = after;
            this.emitter = emitter;
        }

        void wake() {
            woken.release();
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                woken.release();
            }
        }

        @Override
        public void run() {
            int sent = 0;
            String reason = "client_left";
            try {
                long quietSince = System.nanoTime();
                while (!closed.get() && !stopping.get()) {
                    woken.drainPermits();
                    List<TurnEvent> events = OwnerContext.callAs(owner, () -> conversations.eventsAfter(turnId, last));
                    for (TurnEvent event : events) {
                        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type())
                                .data(TurnEvents.dataLine(event)));
                        last = event.sequence();
                        sent++;
                        quietSince = System.nanoTime();
                        if (event.last()) {
                            reason = event.type();
                            emitter.complete();
                            return;
                        }
                    }
                    long quiet = System.nanoTime() - quietSince;
                    if (quiet >= keepAlive.toNanos()) {
                        emitter.send(SseEmitter.event().comment(" " + KEEP_ALIVE));
                        quietSince = System.nanoTime();
                        quiet = 0;
                    }
                    long wait = Math.min(poll.toNanos(), keepAlive.toNanos() - quiet);
                    if (woken.tryAcquire(Math.max(wait, 1), TimeUnit.NANOSECONDS)) {
                        woken.drainPermits();
                    }
                }
                if (stopping.get()) {
                    reason = "stopping";
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException gone) {
                // The client left (or the emitter completed): the job goes on.
                reason = "client_left";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reason = "interrupted";
                emitter.complete();
            } catch (RuntimeException failure) {
                reason = "failed";
                log.atWarn().addKeyValue("turnId", turnId).addKeyValue("errorType", failure.getClass().getName())
                        .log("conversation.stream_failed");
                emitter.complete();
            } finally {
                closed.set(true);
                open.computeIfPresent(turnId, (id, current) -> {
                    current.remove(this);
                    return current.isEmpty() ? null : current;
                });
                log.atInfo().addKeyValue("turnId", turnId).addKeyValue("events", sent).addKeyValue("lastSequence", last)
                        .addKeyValue("reason", reason).log("conversation.stream_closed");
            }
        }
    }
}
