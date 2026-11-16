package io.github.gmshield.accounts.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * A sign-in attempt as Redis holds it. {@code started}: Google has not answered yet. {@code identified}:
 * Google confirmed an identity no account has; the attempt then carries that identity, encrypted for the
 * account id minted for it, until the visitor chooses a username. Nothing in it completes a sign-in: the
 * state, nonce and PKCE verifier are derived from the token, which only the visitor's cookie holds.
 */
public record SignInAttempt(Stage stage, Instant expiresAt, UUID accountId, byte[] googleId, byte[] email) {

    public enum Stage { STARTED, IDENTIFIED }

    public SignInAttempt {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (stage == Stage.IDENTIFIED && (accountId == null || googleId == null || email == null)) {
            throw new IllegalArgumentException("an identified attempt carries the account id and the identity");
        }
    }

    public static SignInAttempt started(Instant expiresAt) {
        return new SignInAttempt(Stage.STARTED, expiresAt, null, null, null);
    }

    public static SignInAttempt identified(Instant expiresAt, UUID accountId, byte[] googleId, byte[] email) {
        return new SignInAttempt(Stage.IDENTIFIED, expiresAt, accountId, googleId, email);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignInAttempt that && stage == that.stage && Objects.equals(expiresAt, that.expiresAt)
                && Objects.equals(accountId, that.accountId) && Arrays.equals(googleId, that.googleId)
                && Arrays.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stage, expiresAt, accountId, Arrays.hashCode(googleId), Arrays.hashCode(email));
    }

    @Override
    public String toString() {
        return "SignInAttempt[stage=" + stage + ", expiresAt=" + expiresAt + ", accountId=" + accountId + "]";
    }
}
