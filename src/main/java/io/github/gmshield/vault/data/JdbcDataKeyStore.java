package io.github.gmshield.vault.data;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code vault_data_key} through {@link JdbcClient}. The client is looked up on first use, so the
 * unit tests, which start without a database, can still create the application.
 *
 * <p>Reads join the caller's transaction, if any. An insert commits in its own transaction
 * ({@code REQUIRES_NEW}, Story 1.11): {@code DataKeys} caches a key as soon as it is stored, so a key
 * written inside a transaction that later rolls back (a first sign-in whose starter library failed) must
 * still be there, or the cache would hold a key whose row is gone. That transaction runs on its own
 * connection: its manager works on a wrapper of the pool, which Spring keys apart from the pool itself,
 * so it never takes over the connection of the caller's JPA transaction.
 */
@Repository
class JdbcDataKeyStore implements DataKeyStore {

    private static final String COLUMNS = "select owner_id, version, wrapped from vault_data_key ";

    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectProvider<DataSource> dataSource;
    private volatile Separate separate;

    JdbcDataKeyStore(ObjectProvider<JdbcClient> jdbc, ObjectProvider<DataSource> dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** A client and a {@code REQUIRES_NEW} template on a connection of their own. */
    private record Separate(JdbcClient jdbc, TransactionTemplate requiresNew) {
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
        Separate own = separate();
        own.requiresNew().executeWithoutResult(status -> own.jdbc()
                .sql("insert into vault_data_key (owner_id, version, wrapped) values (:owner, :version, :wrapped)"
                        + " on conflict (owner_id, version) do nothing")
                .param("owner", key.ownerId())
                .param("version", key.version())
                .param("wrapped", key.wrapped())
                .update());
    }

    private Separate separate() {
        Separate current = separate;
        if (current == null) {
            synchronized (this) {
                current = separate;
                if (current == null) {
                    DataSource own = new DelegatingDataSource(dataSource.getObject());
                    TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(own));
                    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    current = new Separate(JdbcClient.create(own), requiresNew);
                    separate = current;
                }
            }
        }
        return current;
    }
}
