package io.github.gmshield.library.data;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

/** {@code campaign_fingerprint_backfill} in memory (Story 3.2), for tests that run without PostgreSQL. */
public class InMemoryFingerprintBackfill implements FingerprintBackfill {

    public final Set<UUID> owners = new ConcurrentSkipListSet<>();

    @Override
    public List<UUID> owners(int limit) {
        return owners.stream().limit(Math.max(limit, 0)).toList();
    }

    @Override
    public void done(UUID ownerId) {
        owners.remove(ownerId);
    }
}
