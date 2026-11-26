package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code deletion} (Story 3.1 part B, V22): a recorded deletion of the owner's {@code kind}
 * ({@value #SYSTEM}, or from Story 3.2 part B {@value #CAMPAIGN}, V24, or from Story 3.4 part B {@value #MATERIAL} and
 * {@value #VERSION}, V26, or from Story 3.10 part B {@value #CONVERSATION}, V31, or from Story 5.2 part B
 * {@value #ARTIFACT}, V46) {@code targetId}, whose removal may start at {@code notBefore}; {@code doneAt} is null until
 * it ended. Ids and times only.
 */
public record DeletionRow(UUID id, UUID ownerId, String kind, UUID targetId, Instant notBefore, Instant createdAt,
                          Instant doneAt) {

    public static final String SYSTEM = "system";
    public static final String CAMPAIGN = "campaign";
    public static final String MATERIAL = "material";
    public static final String VERSION = "version";
    public static final String CONVERSATION = "conversation";
    public static final String ARTIFACT = "artifact";
}
