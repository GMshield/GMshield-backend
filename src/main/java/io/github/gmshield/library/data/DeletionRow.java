package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code deletion} (Story 3.1 part B, V22): a recorded deletion of the owner's {@code kind}
 * ({@value #SYSTEM}, or from Story 3.2 part B {@value #CAMPAIGN}, V24) {@code targetId}, whose removal may start at
 * {@code notBefore}; {@code doneAt} is null until it ended. Ids and times only.
 */
public record DeletionRow(UUID id, UUID ownerId, String kind, UUID targetId, Instant notBefore, Instant createdAt,
                          Instant doneAt) {

    public static final String SYSTEM = "system";
    public static final String CAMPAIGN = "campaign";
}
