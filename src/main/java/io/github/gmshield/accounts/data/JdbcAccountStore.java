package io.github.gmshield.accounts.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code account} through {@link JdbcClient}. The client is looked up on first use, so the unit tests,
 * which start without a database, can still create the application. A taken unique key is found with
 * {@code on conflict do nothing}: after it, the row that holds the key is committed and visible.
 */
@Repository
class JdbcAccountStore implements AccountStore {

    private static final String COLUMNS = "select id, tier, state, confirmed, google_id, google_id_fingerprint, email,"
            + " email_fingerprint, username, username_fingerprint, terms_version, terms_accepted_at, seal_version, seal"
            + " from account ";

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcAccountStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Insert insert(AccountRow row) {
        int inserted = jdbc.getObject().sql("insert into account (id, tier, state, confirmed, google_id,"
                        + " google_id_fingerprint, email, email_fingerprint, username, username_fingerprint, terms_version,"
                        + " terms_accepted_at, seal_version, seal) values (:id, :tier, :state, :confirmed, :googleId,"
                        + " :googleIdFingerprint, :email, :emailFingerprint, :username, :usernameFingerprint,"
                        + " :termsVersion, :termsAcceptedAt, :sealVersion, :seal) on conflict do nothing")
                .param("id", row.id())
                .param("tier", row.tier())
                .param("state", row.state())
                .param("confirmed", row.confirmed())
                .param("googleId", row.googleId())
                .param("googleIdFingerprint", row.googleIdFingerprint())
                .param("email", row.email())
                .param("emailFingerprint", row.emailFingerprint())
                .param("username", row.username())
                .param("usernameFingerprint", row.usernameFingerprint())
                .param("termsVersion", row.termsVersion())
                .param("termsAcceptedAt", OffsetDateTime.ofInstant(row.termsAcceptedAt(), ZoneOffset.UTC))
                .param("sealVersion", row.sealVersion())
                .param("seal", row.seal())
                .update();
        if (inserted == 1) {
            return Insert.INSERTED;
        }
        boolean identityExists = jdbc.getObject()
                .sql("select exists (select 1 from account where id = :id or google_id_fingerprint = :googleIdFingerprint)")
                .param("id", row.id())
                .param("googleIdFingerprint", row.googleIdFingerprint())
                .query(Boolean.class).single();
        return identityExists ? Insert.IDENTITY_EXISTS : Insert.USERNAME_TAKEN;
    }

    @Override
    public Optional<AccountRow> findById(UUID id) {
        return jdbc.getObject().sql(COLUMNS + "where id = :id").param("id", id).query(JdbcAccountStore::row).optional();
    }

    @Override
    public Optional<AccountRow> findByGoogleIdFingerprint(byte[] fingerprint) {
        return jdbc.getObject().sql(COLUMNS + "where google_id_fingerprint = :fingerprint")
                .param("fingerprint", fingerprint)
                .query(JdbcAccountStore::row)
                .optional();
    }

    @Override
    public boolean updateEmail(AccountRow row, int expectedSealVersion) {
        return jdbc.getObject().sql("update account set email = :email, email_fingerprint = :emailFingerprint,"
                        + " seal_version = :sealVersion, seal = :seal, updated_at = now()"
                        + " where id = :id and seal_version = :expected")
                .param("email", row.email())
                .param("emailFingerprint", row.emailFingerprint())
                .param("sealVersion", row.sealVersion())
                .param("seal", row.seal())
                .param("id", row.id())
                .param("expected", expectedSealVersion)
                .update() == 1;
    }

    private static AccountRow row(ResultSet rs, int rowNumber) throws SQLException {
        return new AccountRow(rs.getObject("id", UUID.class), rs.getString("tier"), rs.getString("state"),
                rs.getBoolean("confirmed"), rs.getBytes("google_id"), rs.getBytes("google_id_fingerprint"),
                rs.getBytes("email"), rs.getBytes("email_fingerprint"), rs.getBytes("username"),
                rs.getBytes("username_fingerprint"), rs.getString("terms_version"),
                rs.getObject("terms_accepted_at", OffsetDateTime.class).toInstant(), rs.getInt("seal_version"),
                rs.getBytes("seal"));
    }
}
