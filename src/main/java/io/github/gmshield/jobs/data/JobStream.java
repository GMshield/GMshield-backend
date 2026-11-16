package io.github.gmshield.jobs.data;

import java.time.Instant;
import java.util.UUID;

/**
 * The Redis work streams. A message has one field, {@code job}, holding a job id; nothing else ever goes in. The
 * backend is the only writer: it adds, creates the consumer group {@code workers} and trims. A Redis failure
 * surfaces as a runtime exception for the caller to log and recover from.
 */
public interface JobStream {

    String GROUP = "workers";
    String FIELD = "job";

    /** {@code XADD <stream> * job <id>}: the new message's id. */
    String offer(String stream, UUID jobId);

    /**
     * {@code XGROUP CREATE <stream> workers 0 MKSTREAM} when the group is missing (a new or a flushed Redis): true
     * when it was created. The group starts at the beginning, so offers made before it existed are still read.
     */
    boolean ensureGroup(String stream);

    /** {@code XTRIM <stream> MINID <time>}: removes the messages added before {@code before}; the number removed. */
    long trimBefore(String stream, Instant before);
}
