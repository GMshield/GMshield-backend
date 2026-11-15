package io.github.gmshield.vault.data;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code vault_data_key} through {@link JdbcClient}. The client is looked up on first use, so the
 * unit tests, which start without a database, can still create the application.
 */
@Repository
class JdbcDataKeyStore implements DataKeyStore {

    private static final String COLUMNS = "select owner_id, version, wrapped from vault_data_key ";

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcDataKeyStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WrappedDataKey> latest(UUID ownerId) {
        return jdbc.getObject().sql(COLUMNS + "where owner_id = :owner order by version desc limit 1")
                .param("owner", ownerId)
                .query((rs, n) -> new WrappedDataKey(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBytes(3)))
                .optional();
    }

    @Override
    public Optional<WrappedDataKey> find(UUID ownerId, int version) {
        return jdbc.getObject().sql(COLUMNS + "where owner_id = :owner and version = :version")
                .param("owner", ownerId)
                .param("version", version)
                .query((rs, n) -> new WrappedDataKey(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBytes(3)))
                .optional();
    }

    @Override
    public void insertIfAbsent(WrappedDataKey key) {
        jdbc.getObject().sql("insert into vault_data_key (owner_id, version, wrapped) values (:owner, :version, :wrapped)"
                        + " on conflict (owner_id, version) do nothing")
                .param("owner", key.ownerId())
                .param("version", key.version())
                .param("wrapped", key.wrapped())
                .update();
    }
}
