package io.github.gmshield.accounts.service;

import io.github.gmshield.accounts.data.Session;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SessionStore.Pointer;
import io.github.gmshield.audit.service.AuditLog;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.StartupSettings.SessionTimings;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Sessions that rotate and end (Story 1.10). A session has one current token and, after a renewal, one
 * previous token that ordinary calls may still use until the overlap ends. Redis keeps only the tokens'
 * vault hashes (see {@link SessionStore}); the decisions are made here and written with a compare-and-set
 * on the session's version, so two renewals of one token never both issue a token.
 *
 * <ul>
 *   <li>Only sign-in ({@link #start}) and {@link #renew} issue a token; an ordinary call never does.</li>
 *   <li>A session ends after the idle timeout without a call, or at the absolute timeout.</li>
 *   <li>The previous token after the overlap, once the newer token has served a call, or any older token,
 *       is reuse: the session ends and {@code session.token_reused} is audited. The previous token after the
 *       overlap while the newer one was never used is refused, and the session kept.</li>
 *   <li>An account whose seal version rose above the session's answers {@code session.renewal_required} on
 *       every call except a renewal, which issues the next token under the current seal version.</li>
 * </ul>
 */
@Service
public class Sessions {

    static final String TOKEN_PURPOSE = "session";
    /** {@code lastSeenAt} is written at most this often, so ordinary calls rarely write. */
    static final Duration SEEN_WRITE_INTERVAL = Duration.ofMinutes(1);
    static final String AUDIT_TOKEN_REUSED = "session.token_reused";
    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    private final SessionStore store;
    private final Accounts accounts;
    private final Vault vault;
    private final AuditLog audit;
    private final SessionTimings timings;
    private final Clock clock;

    Sessions(SessionStore store, Accounts accounts, Vault vault, AuditLog audit, SessionTimings timings,
             ObjectProvider<Clock> clock) {
        this.store = store;
        this.accounts = accounts;
        this.vault = vault;
        this.audit = audit;
        this.timings = timings;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A newly issued token, never stored, with what is left of the absolute timeout and when to renew it. */
    public record Grant(String token, Duration expiresIn, Duration renewAfter) {
        @Override
        public String toString() {
            return "Grant[expiresIn=" + expiresIn + ", renewAfter=" + renewAfter + "]";
        }
    }

    /** How a presented token relates to the session its pointer names. */
    private enum Use { CURRENT, IN_OVERLAP, STALE, REUSED_PREVIOUS, REUSED_OLDER }

    /** A new session for the account. */
    Grant start(Account account) {
        Instant now = now();
        String token = Tokens.newToken();
        byte[] hash = hash(token);
        Session session = Session.started(Ids.newId(), account.id(), account.sealVersion(), now, hash);
        Duration timeToLive = remaining(session, now);
        store.create(session, new Pointer(hash, timeToLive), timeToLive, timings.absoluteTimeout());
        log.atInfo().addKeyValue("accountId", account.id()).addKeyValue("sessionId", session.id()).log("session.started");
        return grant(token, session, now);
    }

    /**
     * The active account of a session token, for an ordinary call. No token, an unknown, ended, stale or reused
     * one, or an account that is not active: {@code session.required}. A seal version above the session's:
     * {@code session.renewal_required}. A tampered account: {@code integrity.failed}.
     */
    public Account account(String token) {
        byte[] hash = wellFormedHash(token);
        for (int attempt = 0; ; attempt++) {
            Instant now = now();
            Session session = live(hash, now);
            Use use = useOf(session, hash, now);
            if (use != Use.CURRENT && use != Use.IN_OVERLAP) {
                if (refuseOrRetry(session, use, attempt)) {
                    continue;
                }
            }
            Account account = activeAccount(session);
            if (account.sealVersion() > session.sealVersion()) {
                refused(session, "seal_version");
                throw new ApiException(AccountProblems.RENEWAL_REQUIRED);
            }
            boolean byCurrent = use == Use.CURRENT;
            boolean write = (byCurrent && !session.currentUsed())
                    || !now.isBefore(session.lastSeenAt().plus(SEEN_WRITE_INTERVAL));
            if (!write) {
                return account;
            }
            Session next = session.seen(now, byCurrent);
            Duration timeToLive = remaining(next, now);
            // The write is best-effort: when it is lost again on the retry, another call has recorded the session
            // as seen meanwhile, so the call is served rather than refused.
            if (store.replace(session.version(), next, List.of(new Pointer(hex(next.currentHash()), timeToLive)), timeToLive)
                    || attempt > 0) {
                return account;
            }
        }
    }

    /**
     * Renews a session with its current token: a new token, and the presented one kept as the previous token
     * until the overlap ends. The previous token within the overlap: {@code session.already_renewed} (409), the
     * session continues. Anything else that {@link #account} refuses: {@code session.required}. A risen seal
     * version is no refusal here: the new token is issued under the account's current one.
     */
    public Grant renew(String token) {
        byte[] hash = wellFormedHash(token);
        for (int attempt = 0; ; attempt++) {
            Instant now = now();
            Session session = live(hash, now);
            Use use = useOf(session, hash, now);
            if (use == Use.IN_OVERLAP) {
                refused(session, "already_renewed");
                throw new ApiException(AccountProblems.ALREADY_RENEWED);
            }
            if (use != Use.CURRENT) {
                if (refuseOrRetry(session, use, attempt)) {
                    continue;
                }
            }
            Account account = activeAccount(session);
            String newToken = Tokens.newToken();
            byte[] newHash = hash(newToken);
            Session next = session.renewed(newHash, account.sealVersion(), now, now.plus(timings.overlap()));
            Duration timeToLive = remaining(next, now);
            // The retired token's pointer lives to the absolute end, so its reuse is still recognised then.
            List<Pointer> pointers = List.of(new Pointer(newHash, timeToLive),
                    new Pointer(hash, positive(absoluteEnd(session).toEpochMilli() - now.toEpochMilli())));
            if (store.replace(session.version(), next, pointers, timeToLive)) {
                log.atInfo().addKeyValue("accountId", session.accountId()).addKeyValue("sessionId", session.id())
                        .addKeyValue("sealVersion", next.sealVersion()).log("session.renewed");
                return grant(newToken, next, now);
            }
            retryOnce(session, attempt, "conflict");
        }
    }

    /** Ends the session of this token, if there is one (sign-out). */
    public void end(String token) {
        if (!Tokens.wellFormed(token)) {
            return;
        }
        byte[] hash = hash(token);
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Session> session = store.findByToken(hash);
            if (session.isEmpty()) {
                return;
            }
            if (store.delete(session.get())) {
                log.atInfo().addKeyValue("accountId", session.get().accountId()).addKeyValue("sessionId", session.get().id())
                        .addKeyValue("reason", "sign_out").log("session.ended");
                return;
            }
        }
    }

    /** The token's vault hash, or {@code session.required} for a value that cannot be a token. */
    private byte[] wellFormedHash(String token) {
        if (!Tokens.wellFormed(token)) {
            throw new ApiException(AccountProblems.SESSION_REQUIRED);
        }
        return hash(token);
    }

    /** The session of the token, or {@code session.required} when there is none or it timed out (then it ends). */
    private Session live(byte[] hash, Instant now) {
        Session session = store.findByToken(hash).orElseThrow(() -> new ApiException(AccountProblems.SESSION_REQUIRED));
        boolean idle = !now.isBefore(session.lastSeenAt().plus(timings.idleTimeout()));
        if (idle || !now.isBefore(absoluteEnd(session))) {
            // A conflicting write cannot revive it: the next call finds it timed out again, or gone.
            store.delete(session);
            log.atInfo().addKeyValue("accountId", session.accountId()).addKeyValue("sessionId", session.id())
                    .addKeyValue("reason", idle ? "idle_timeout" : "absolute_timeout").log("session.ended");
            throw new ApiException(AccountProblems.SESSION_REQUIRED);
        }
        return session;
    }

    private static Use useOf(Session session, byte[] hash, Instant now) {
        String presented = Session.hex(hash);
        if (presented.equals(session.currentHash())) {
            return Use.CURRENT;
        }
        if (presented.equals(session.previousHash())) {
            if (now.isBefore(session.overlapUntil())) {
                return Use.IN_OVERLAP;
            }
            return session.currentUsed() ? Use.REUSED_PREVIOUS : Use.STALE;
        }
        // A token two or more renewals old: a newer one has served at least the renewal after it.
        return Use.REUSED_OLDER;
    }

    /**
     * Refuses a stale token, or ends the session of a reused one and audits it. True when the session changed
     * meanwhile and this is the first attempt: the caller decides again on the session as it is now.
     */
    private boolean refuseOrRetry(Session session, Use use, int attempt) {
        if (use == Use.STALE) {
            refused(session, "stale_token");
            throw new ApiException(AccountProblems.SESSION_REQUIRED);
        }
        if (!store.delete(session)) {
            retryOnce(session, attempt, "conflict");
            return true;
        }
        log.atWarn().addKeyValue("accountId", session.accountId()).addKeyValue("sessionId", session.id())
                .addKeyValue("reason", "token_reused").log("session.ended");
        // The session is gone first; the audit entry second, and its failure never lets the token through.
        try {
            audit.append(AUDIT_TOKEN_REUSED, session.accountId(), Map.of("sessionId", session.id(),
                    "reusedToken", use == Use.REUSED_PREVIOUS ? "previous" : "older"));
        } catch (RuntimeException failed) {
            log.atError().setCause(failed).addKeyValue("accountId", session.accountId())
                    .addKeyValue("action", AUDIT_TOKEN_REUSED).log("audit.append_failed");
        }
        throw new ApiException(AccountProblems.SESSION_REQUIRED);
    }

    /** After a lost compare-and-set: one more decision on the session as it is now, then {@code session.required}. */
    private static void retryOnce(Session session, int attempt, String reason) {
        if (attempt > 0) {
            refused(session, reason);
            throw new ApiException(AccountProblems.SESSION_REQUIRED);
        }
    }

    private Account activeAccount(Session session) {
        Account account = accounts.byId(session.accountId()).filter(Account::active).orElse(null);
        if (account == null || account.sealVersion() < session.sealVersion()) {
            refused(session, account == null ? "account_not_active" : "seal_version_lower");
            throw new ApiException(AccountProblems.SESSION_REQUIRED);
        }
        return account;
    }

    private static void refused(Session session, String reason) {
        log.atInfo().addKeyValue("accountId", session.accountId()).addKeyValue("sessionId", session.id())
                .addKeyValue("reason", reason).log("session.refused");
    }

    /** In the last renew-after window, renewal is due only when the session ends, so it never renews on every call. */
    private Grant grant(String token, Session session, Instant now) {
        Duration expiresIn = Duration.between(now, absoluteEnd(session));
        Duration renewAfter = timings.renewAfter().compareTo(expiresIn) <= 0 ? timings.renewAfter() : expiresIn;
        return new Grant(token, expiresIn, renewAfter);
    }

    private Instant absoluteEnd(Session session) {
        return session.createdAt().plus(timings.absoluteTimeout());
    }

    /** What is left of the shorter of the idle and the absolute timeout: every key's time to live. */
    private Duration remaining(Session session, Instant now) {
        Instant idleEnd = session.lastSeenAt().plus(timings.idleTimeout());
        Instant end = idleEnd.isBefore(absoluteEnd(session)) ? idleEnd : absoluteEnd(session);
        return positive(end.toEpochMilli() - now.toEpochMilli());
    }

    private static Duration positive(long millis) {
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private byte[] hash(String token) {
        return vault.tokenHash(TOKEN_PURPOSE, token);
    }

    private static byte[] hex(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }
}
