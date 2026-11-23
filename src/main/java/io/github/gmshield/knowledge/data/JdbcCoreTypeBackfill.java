package io.github.gmshield.knowledge.data;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link MissingCoreTypes} through {@link JdbcClient} (Story 4.1): the fill at start has no owner, so it cannot open the
 * application's (JPA) transaction to list the systems across owners. One statement over ids only: the ready systems
 * with fewer core record types than there are core keys. The client is looked up on first use, so the unit tests, which
 * start without a database, can still create the application.
 */
@Repository
class JdbcCoreTypeBackfill implements MissingCoreTypes {

    /** Below every UUID: the cursor of the first page. */
    private static final UUID BOTTOM = new UUID(0L, 0L);
    private static final String MISSING = "select s.owner_id, s.id from system s where s.state = 'ready'"
            + " and s.id > :after and (select count(*) from record_type r where r.owner_id = s.owner_id"
            + " and r.system_id = s.id and r.core is not null) < :cores order by s.id limit :limit";

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcCoreTypeBackfill(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SystemRef> systems(UUID after, int cores, int limit) {
        return jdbc.getObject().sql(MISSING).param("after", after == null ? BOTTOM : after).param("cores", cores)
                .param("limit", limit)
                .query((row, number) -> new SystemRef(row.getObject(1, UUID.class), row.getObject(2, UUID.class)))
                .list();
    }
}
