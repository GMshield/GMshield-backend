package io.github.gmshield.jobs.data;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.RedisConnectionFailureException;

/** The work streams in memory. {@link #down} makes every command fail as an unreachable Redis does. */
public class InMemoryJobStream implements JobStream {

    public record Message(String id, String stream, UUID jobId, Instant at) {
    }

    public final Map<String, List<Message>> streams = new ConcurrentHashMap<>();
    public final Set<String> groups = ConcurrentHashMap.newKeySet();
    public final AtomicBoolean down = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    public InMemoryJobStream(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String offer(String stream, UUID jobId) {
        failIfDown();
        Instant at = clock.instant();
        String id = at.toEpochMilli() + "-" + sequence.incrementAndGet();
        streams.computeIfAbsent(stream, ignored -> new ArrayList<>()).add(new Message(id, stream, jobId, at));
        return id;
    }

    @Override
    public synchronized boolean ensureGroup(String stream) {
        failIfDown();
        streams.computeIfAbsent(stream, ignored -> new ArrayList<>());
        return groups.add(stream);
    }

    @Override
    public synchronized long trimBefore(String stream, Instant before) {
        failIfDown();
        List<Message> messages = streams.getOrDefault(stream, new ArrayList<>());
        int size = messages.size();
        messages.removeIf(message -> message.at().toEpochMilli() < before.toEpochMilli());
        return size - messages.size();
    }

    /** The job ids offered on a stream, in order. */
    public synchronized List<UUID> offered(String stream) {
        return streams.getOrDefault(stream, List.of()).stream().map(Message::jobId).toList();
    }

    public synchronized void clear() {
        streams.clear();
        groups.clear();
        down.set(false);
    }

    private void failIfDown() {
        if (down.get()) {
            throw new RedisConnectionFailureException("redis down");
        }
    }
}
