package io.github.gmshield.materials.data;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link PendingRemovals} through {@link JdbcClient}: the sweep has no owner, so it cannot open the application's
 * (JPA) transaction, and Hibernate filters every JPQL read by the owner. One statement, on its own outside any
 * transaction, over {@code material_version}'s ids and flag only (the partial index of V13). The client is looked up
 * on first use, so the unit tests, which start without a database, can still create the application.
 */
@Repository
class JdbcPendingRemovals implements PendingRemovals {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcPendingRemovals(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> list(int limit) {
        return jdbc.getObject().sql("select owner_id, id from material_version where removal_pending"
                        + " order by updated_at, id limit :limit")
                .param("limit", limit)
                .query((rs, row) -> new Pending(rs.getObject("owner_id", UUID.class), rs.getObject("id", UUID.class)))
                .list();
    }
}
