package io.github.gmshield.vault.data;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code vault_probe} through {@link JdbcClient}; exists only under the {@code test-mode} profile. */
@Repository
@Profile("test-mode")
class JdbcProbeStore implements ProbeStore {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcProbeStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ProbeRow row) {
        jdbc.getObject().sql("insert into vault_probe (id, owner_id, text_value, text_fingerprint, file_id)"
                        + " values (:id, :owner, :text, :fingerprint, :file)")
                .param("id", row.id())
                .param("owner", row.ownerId())
                .param("text", row.text())
                .param("fingerprint", row.textFingerprint())
                .param("file", row.fileId())
                .update();
    }

    @Override
    public Optional<ProbeRow> find(UUID id) {
        return jdbc.getObject().sql("select id, owner_id, text_value, text_fingerprint, file_id from vault_probe where id = :id")
                .param("id", id)
                .query((rs, n) -> new ProbeRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBytes(3), rs.getBytes(4), rs.getObject(5, UUID.class)))
                .optional();
    }
}
