package io.github.gmshield.artifacts.data;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link StaleArtifacts} through {@link JdbcClient}, as the materials' {@code JdbcStaleIndexes}: one statement on its
 * own, outside any transaction, over the owners, ids, numbers and recipes of {@code record_version} and
 * {@code artifact_index} only. The client is looked up on first use, so the unit tests, which start without a database,
 * can still create the application.
 */
@Repository
class JdbcStaleArtifacts implements StaleArtifacts {

    private static final String OWNERS = "select distinct v.owner_id from record_version v"
            + " where v.number = (select max(w.number) from record_version w where w.record_id = v.record_id)"
            + " and not exists (select 1 from artifact_index i where i.record_id = v.record_id"
            + " and i.number = v.number and i.recipe = :recipe) order by v.owner_id";

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcStaleArtifacts(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> owners(String recipe) {
        return jdbc.getObject().sql(OWNERS).param("recipe", recipe)
                .query((rs, row) -> rs.getObject("owner_id", UUID.class)).list();
    }
}
