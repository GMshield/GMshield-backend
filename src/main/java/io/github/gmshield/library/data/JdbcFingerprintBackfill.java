package io.github.gmshield.library.data;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FingerprintBackfill} through {@link JdbcClient}: the fill at start has no owner, so it cannot open the
 * application's (JPA) transaction to list the owners. Each statement runs on its own, over owner ids only. The client is
 * looked up on first use, so the unit tests, which start without a database, can still create the application.
 */
@Repository
class JdbcFingerprintBackfill implements FingerprintBackfill {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcFingerprintBackfill(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> owners(int limit) {
        return jdbc.getObject().sql("select owner_id from campaign_fingerprint_backfill order by owner_id limit :limit")
                .param("limit", limit).query(UUID.class).list();
    }

    @Override
    public void done(UUID ownerId) {
        jdbc.getObject().sql("delete from campaign_fingerprint_backfill where owner_id = :owner")
                .param("owner", ownerId).update();
    }
}
