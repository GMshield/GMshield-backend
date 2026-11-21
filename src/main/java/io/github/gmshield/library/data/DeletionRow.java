package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code deletion} (Story 3.1 part B, V22): a recorded deletion of the owner's {@code kind}
 * ({@value #SYSTEM}) {@code targetId}, whose removal may start at {@code notBefore}; {@code doneAt} is null until it
 * ended. Ids and times only.
 */
public record DeletionRow(UUID id, UUID ownerId, String kind, UUID targetId, Instant notBefore, Instant createdAt,
                          Instant doneAt) {

    public static final String SYSTEM = "system";
}
