package io.github.gmshield.accounts.service;

import io.github.gmshield.accounts.data.Session;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The minimal session of Story 1.9: a random token that only the visitor's cookie holds, stored in Redis as
 * its vault hash with the account id and the seal version, for 7 days. Sign-out ends it. Rotation, the idle
 * timeout and the seal-version check come with Story 1.10.
 */
@Service
public class Sessions {

    public static final Duration LIFETIME = Duration.ofDays(7);
    static final String TOKEN_PURPOSE = "session";
    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    private final SessionStore store;
    private final Accounts accounts;
    private final Vault vault;
    private final Clock clock;

    Sessions(SessionStore store, Accounts accounts, Vault vault, ObjectProvider<Clock> clock) {
        this.store = store;
        this.accounts = accounts;
        this.vault = vault;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A new session for the account; its token, which is never stored. */
    String start(Account account) {
        String token = Tokens.newToken();
        store.create(vault.tokenHash(TOKEN_PURPOSE, token), new Session(account.id(), account.sealVersion(), clock.instant()),
                LIFETIME);
        log.atInfo().addKeyValue("accountId", account.id()).log("session.started");
        return token;
    }

    /**
     * The active account of a session token. No token, an unknown or ended session, or an account that is
     * not active: {@code session.required}. A tampered account: {@code integrity.failed}.
     */
    public Account account(String token) {
        if (!Tokens.wellFormed(token)) {
            throw new ApiException(AccountProblems.SESSION_REQUIRED);
        }
        Session session = store.find(vault.tokenHash(TOKEN_PURPOSE, token))
                .orElseThrow(() -> new ApiException(AccountProblems.SESSION_REQUIRED));
        return accounts.byId(session.accountId())
                .filter(Account::active)
                .orElseThrow(() -> new ApiException(AccountProblems.SESSION_REQUIRED));
    }

    /** Ends the session of this token, if it exists. */
    public void end(String token) {
        if (Tokens.wellFormed(token)) {
            store.delete(vault.tokenHash(TOKEN_PURPOSE, token));
            log.atInfo().log("session.ended");
        }
    }
}
