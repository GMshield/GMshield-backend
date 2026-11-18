package io.github.gmshield.materials.data;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link StaleIndexes} through {@link JdbcClient}, as {@link JdbcPendingRemovals}: one statement on its own, outside
 * any transaction, over {@code material_version}'s owner, state and recipe only (the partial index of V15). The client
 * is looked up on first use, so the unit tests, which start without a database, can still create the application.
 */
@Repository
class JdbcStaleIndexes implements StaleIndexes {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcStaleIndexes(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> owners(String recipe) {
        return jdbc.getObject().sql("select distinct owner_id from material_version where state = 'ready'"
                        + " and index_recipe is distinct from :recipe order by owner_id")
                .param("recipe", recipe)
                .query((rs, row) -> rs.getObject("owner_id", UUID.class))
                .list();
    }
}
