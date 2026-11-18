package io.github.gmshield.accounts.service;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates, loads and changes accounts (AD-19, AD-20). The username, the email address and the Google id
 * are encrypted for the account itself ({@code Binding("account", field, id, id)}), each beside a
 * fingerprint. The seal covers the id, tier, state, the Google-id, username and email fingerprints, the
 * confirmed flag and the seal version, and is checked on every load: a mismatch is
 * {@code integrity.failed} and grants nothing.
 */
@Service
public class Accounts {

    static final String ENTITY = "account";
    static final String FIELD_GOOGLE_ID = "google_id";
    static final String FIELD_EMAIL = "email";
    static final String FIELD_USERNAME = "username";
    private static final Logger log = LoggerFactory.getLogger(Accounts.class);

    private final AccountStore store;
    private final Vault vault;

    Accounts(AccountStore store, Vault vault) {
        this.store = store;
        this.vault = vault;
    }

    /** The account of a Google identity, if one exists; its seal checked. */
    public Optional<Account> byGoogleId(String googleId) {
        return store.findByGoogleIdFingerprint(googleIdFingerprint(googleId)).map(this::open);
    }

    /** The account with this id, if one exists; its seal checked. */
    public Optional<Account> byId(UUID id) {
        return store.findById(id).map(this::open);
    }

    /** The Google id and the email address encrypted for an account that does not exist yet. */
    byte[] encryptGoogleId(UUID accountId, String googleId) {
        return vault.encryptText(binding(FIELD_GOOGLE_ID, accountId), googleId);
    }

    byte[] encryptEmail(UUID accountId, String email) {
        return vault.encryptText(binding(FIELD_EMAIL, accountId), email);
    }

    /**
     * Creates an ordinary, active, confirmed account from an identity already encrypted for {@code id}.
     * Throws {@code request.username_taken} when the username is taken whatever its case, and
     * {@code request.sign_in_expired} when the id or the Google identity has an account already.
     */
    Account create(UUID id, byte[] encryptedGoogleId, byte[] encryptedEmail, String username, String termsVersion,
                   Instant termsAcceptedAt) {
        String googleId = vault.decryptText(binding(FIELD_GOOGLE_ID, id), encryptedGoogleId);
        String email = vault.decryptText(binding(FIELD_EMAIL, id), encryptedEmail);
        byte[] googleIdFingerprint = googleIdFingerprint(googleId);
        byte[] emailFingerprint = emailFingerprint(email);
        byte[] usernameFingerprint = vault.fingerprint(ENTITY, FIELD_USERNAME, Usernames.uniqueForm(username));
        int sealVersion = 1;
        AccountRow row = new AccountRow(id, Account.TIER_ORDINARY, Account.STATE_ACTIVE, true,
                encryptedGoogleId, googleIdFingerprint, encryptedEmail, emailFingerprint,
                vault.encryptText(binding(FIELD_USERNAME, id), username), usernameFingerprint,
                termsVersion, termsAcceptedAt, sealVersion,
                seal(id, Account.TIER_ORDINARY, Account.STATE_ACTIVE, googleIdFingerprint, usernameFingerprint,
                        emailFingerprint, true, sealVersion));
        switch (store.insert(row)) {
            case INSERTED -> {
                log.atInfo().addKeyValue("accountId", id).log("account.created");
                return new Account(id, row.tier(), row.state(), true, googleId, email, username, sealVersion);
            }
            case USERNAME_TAKEN -> throw new ApiException(AccountProblems.USERNAME_TAKEN);
            default -> {
                log.atInfo().addKeyValue("accountId", id).log("account.exists");
                throw new ApiException(AccountProblems.SIGN_IN_EXPIRED);
            }
        }
    }

    /**
     * The account with the email address Google now asserts. When it differs, the address and its
     * fingerprint are replaced, the seal version rises and the account is sealed again.
     */
    Account withEmail(Account account, String email) {
        if (account.email().equals(email)) {
            return account;
        }
        AccountRow current = store.findById(account.id()).orElseThrow(() -> new ApiException(AccountProblems.SIGN_IN_EXPIRED));
        Account checked = open(current);
        int sealVersion = checked.sealVersion() + 1;
        byte[] emailFingerprint = emailFingerprint(email);
        AccountRow changed = current.withEmail(encryptEmail(account.id(), email), emailFingerprint, sealVersion,
                seal(current.id(), current.tier(), current.state(), current.googleIdFingerprint(),
                        current.usernameFingerprint(), emailFingerprint, current.confirmed(), sealVersion));
        if (!store.updateEmail(changed, checked.sealVersion())) {
            // Another change came first: use the account as it is now.
            return byId(account.id()).orElseThrow(() -> new ApiException(AccountProblems.SIGN_IN_EXPIRED));
        }
        log.atInfo().addKeyValue("accountId", account.id()).addKeyValue("sealVersion", sealVersion).log("account.email_changed");
        return open(changed);
    }

    /** Checks the seal, then decrypts. A row whose sealed fields were changed never reaches a caller. */
    private Account open(AccountRow row) {
        vault.verifySeal(ENTITY, row.id(), row.seal(), sealed(row.tier(), row.state(), row.googleIdFingerprint(),
                row.usernameFingerprint(), row.emailFingerprint(), row.confirmed(), row.sealVersion()));
        String googleId = vault.decryptText(binding(FIELD_GOOGLE_ID, row.id()), row.googleId());
        String email = vault.decryptText(binding(FIELD_EMAIL, row.id()), row.email());
        String username = vault.decryptText(binding(FIELD_USERNAME, row.id()), row.username());
        return new Account(row.id(), row.tier(), row.state(), row.confirmed(), googleId, email, username, row.sealVersion());
    }

    private byte[] seal(UUID id, String tier, String state, byte[] googleIdFingerprint, byte[] usernameFingerprint,
                        byte[] emailFingerprint, boolean confirmed, int sealVersion) {
        return vault.seal(ENTITY, id, sealed(tier, state, googleIdFingerprint, usernameFingerprint, emailFingerprint,
                confirmed, sealVersion));
    }

    /** The sealed values after the id, in their fixed order. */
    private static String[] sealed(String tier, String state, byte[] googleIdFingerprint, byte[] usernameFingerprint,
                                   byte[] emailFingerprint, boolean confirmed, int sealVersion) {
        HexFormat hex = HexFormat.of();
        return new String[] {tier, state, hex.formatHex(googleIdFingerprint), hex.formatHex(usernameFingerprint),
                hex.formatHex(emailFingerprint), Boolean.toString(confirmed), Integer.toString(sealVersion)};
    }

    private byte[] googleIdFingerprint(String googleId) {
        return vault.fingerprint(ENTITY, FIELD_GOOGLE_ID, googleId);
    }

    private byte[] emailFingerprint(String email) {
        return vault.fingerprint(ENTITY, FIELD_EMAIL, email.toLowerCase(Locale.ROOT));
    }

    private static Binding binding(String field, UUID accountId) {
        return new Binding(ENTITY, field, accountId, accountId);
    }
}
