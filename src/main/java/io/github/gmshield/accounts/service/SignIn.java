package io.github.gmshield.accounts.service;

import io.github.gmshield.accounts.data.SignInAttempt;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Sign-in with Google, run by the backend (AD-8). Starting issues an attempt token valid for 10 minutes;
 * Redis keeps only its vault hash. The OAuth state, the nonce and the PKCE verifier are derived from the
 * token with {@code vault.tokenHash} under separate purposes, so Redis holds nothing that completes a
 * sign-in, and only the browser that holds the token's cookie can finish it.
 *
 * <p>A callback takes the attempt out of Redis in one step: a second callback with the same attempt, a
 * wrong state or an expired attempt is {@code request.sign_in_expired}, and nothing is created. A known
 * Google identity gets a session; an unknown one is kept, encrypted for a newly minted account id, in the
 * attempt until the visitor chooses a username and accepts the terms.
 */
@Service
public class SignIn {

    public static final Duration ATTEMPT_LIFETIME = Duration.ofMinutes(10);
    /** The version of the draft terms of use and privacy notice the visitor accepts (Epic 10 replaces them). */
    public static final String TERMS_VERSION = "2026-11-16-draft";
    private static final Logger log = LoggerFactory.getLogger(SignIn.class);

    private final SignInAttemptStore attempts;
    private final Accounts accounts;
    private final Sessions sessions;
    private final GoogleSignIn google;
    private final Vault vault;
    private final Clock clock;

    SignIn(SignInAttemptStore attempts, Accounts accounts, Sessions sessions, GoogleSignIn google, Vault vault,
           ObjectProvider<Clock> clock) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.sessions = sessions;
        this.google = google;
        this.vault = vault;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public record Started(String attemptToken, String authorizationUrl, Duration lifetime) {
        @Override
        public String toString() {
            return "Started[hidden]";
        }
    }

    /** A signed-in visitor's session token, or none when the username step comes next. */
    public record Outcome(Optional<String> sessionToken) {
        @Override
        public String toString() {
            return "Outcome[signedIn=" + sessionToken.isPresent() + "]";
        }
    }

    public Started start() {
        String token = Tokens.newToken();
        attempts.save(attemptKey(token), SignInAttempt.started(clock.instant().plus(ATTEMPT_LIFETIME)), ATTEMPT_LIFETIME);
        log.atInfo().log("sign_in.started");
        return new Started(token, google.authorizationUrl(state(token), nonce(token), vault.pkceChallenge(verifier(token))),
                ATTEMPT_LIFETIME);
    }

    /** Google's redirect, relayed by the frontend with the attempt token of the visitor's cookie. */
    public Outcome callback(String attemptToken, String state, String code, String error) {
        if (!Tokens.wellFormed(attemptToken)) {
            throw expired("no_attempt");
        }
        SignInAttempt attempt = attempts.take(attemptKey(attemptToken)).orElseThrow(() -> expired("no_attempt"));
        Instant now = clock.instant();
        if (attempt.stage() != SignInAttempt.Stage.STARTED) {
            throw expired("reused");
        }
        if (!now.isBefore(attempt.expiresAt())) {
            throw expired("expired");
        }
        if (!Tokens.sameText(state(attemptToken), state)) {
            throw expired("state");
        }
        if (error != null || code == null || code.isBlank()) {
            log.atInfo().addKeyValue("reason", "google_error").log("sign_in.refused");
            throw new ApiException(AccountProblems.SIGN_IN_REFUSED);
        }
        GoogleIdentity identity = google.exchange(code, verifier(attemptToken), nonce(attemptToken));
        Optional<Account> known = accounts.byGoogleId(identity.subject());
        if (known.isPresent()) {
            if (!known.get().active()) {
                // Refused before any change: a blocked or deleting account is left exactly as it is.
                log.atInfo().addKeyValue("reason", "account_" + known.get().state()).addKeyValue("accountId", known.get().id())
                        .log("sign_in.refused");
                throw new ApiException(AccountProblems.SIGN_IN_REFUSED);
            }
            Account account = accounts.withEmail(known.get(), identity.email());
            log.atInfo().addKeyValue("accountId", account.id()).log("sign_in.returning");
            return new Outcome(Optional.of(sessions.start(account)));
        }
        UUID accountId = Ids.newId();
        attempts.save(attemptKey(attemptToken), SignInAttempt.identified(attempt.expiresAt(), accountId,
                        accounts.encryptGoogleId(accountId, identity.subject()), accounts.encryptEmail(accountId, identity.email())),
                Duration.between(now, attempt.expiresAt()));
        log.atInfo().log("sign_in.username_required");
        return new Outcome(Optional.empty());
    }

    /**
     * The username step of a first sign-in: creates the account, ends the attempt and starts a session.
     * The attempt stays when the username is taken or invalid, or the terms are not accepted, so the
     * visitor can try again within the attempt's 10 minutes.
     */
    public String complete(String attemptToken, String username, boolean termsAccepted) {
        if (!Tokens.wellFormed(attemptToken)) {
            throw expired("no_attempt");
        }
        byte[] key = attemptKey(attemptToken);
        SignInAttempt attempt = attempts.find(key).orElseThrow(() -> expired("no_attempt"));
        Instant now = clock.instant();
        if (attempt.stage() != SignInAttempt.Stage.IDENTIFIED || !now.isBefore(attempt.expiresAt())) {
            throw expired(attempt.stage() != SignInAttempt.Stage.IDENTIFIED ? "not_identified" : "expired");
        }
        if (!Usernames.valid(username)) {
            throw new ApiException(AccountProblems.USERNAME_INVALID);
        }
        if (!termsAccepted) {
            throw new ApiException(AccountProblems.TERMS_REQUIRED);
        }
        Account account;
        try {
            account = accounts.create(attempt.accountId(), attempt.googleId(), attempt.email(), username,
                    TERMS_VERSION, now);
        } catch (ApiException failed) {
            if (failed.problem().equals(AccountProblems.SIGN_IN_EXPIRED)) {
                // The identity has an account already (another attempt finished first): this one is over.
                attempts.delete(key);
            }
            throw failed;
        }
        attempts.delete(key);
        return sessions.start(account);
    }

    private byte[] attemptKey(String token) {
        return vault.tokenHash("sign-in", token);
    }

    private String state(String token) {
        return Tokens.base64Url(vault.tokenHash("sign-in-state", token));
    }

    private String nonce(String token) {
        return Tokens.base64Url(vault.tokenHash("sign-in-nonce", token));
    }

    /** 43 characters, as PKCE requires (RFC 7636: 43 to 128). */
    private String verifier(String token) {
        return Tokens.base64Url(vault.tokenHash("sign-in-pkce", token));
    }

    private static ApiException expired(String reason) {
        log.atInfo().addKeyValue("reason", reason).log("sign_in.refused");
        return new ApiException(AccountProblems.SIGN_IN_EXPIRED);
    }
}
