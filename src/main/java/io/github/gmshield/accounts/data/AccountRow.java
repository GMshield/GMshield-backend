package io.github.gmshield.accounts.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code account} as stored: the Google id, the email address and the username are vault
 * ciphertexts, each beside its fingerprint. Equality compares the bytes; {@code toString} shows ids,
 * tier, state and lengths only, never a stored value.
 */
public record AccountRow(
        UUID id, String tier, String state, boolean confirmed,
        byte[] googleId, byte[] googleIdFingerprint,
        byte[] email, byte[] emailFingerprint,
        byte[] username, byte[] usernameFingerprint,
        String termsVersion, Instant termsAcceptedAt, int sealVersion, byte[] seal) {

    @Override
    public boolean equals(Object other) {
        return other instanceof AccountRow that
                && Objects.equals(id, that.id) && Objects.equals(tier, that.tier) && Objects.equals(state, that.state)
                && confirmed == that.confirmed
                && Arrays.equals(googleId, that.googleId) && Arrays.equals(googleIdFingerprint, that.googleIdFingerprint)
                && Arrays.equals(email, that.email) && Arrays.equals(emailFingerprint, that.emailFingerprint)
                && Arrays.equals(username, that.username) && Arrays.equals(usernameFingerprint, that.usernameFingerprint)
                && Objects.equals(termsVersion, that.termsVersion) && Objects.equals(termsAcceptedAt, that.termsAcceptedAt)
                && sealVersion == that.sealVersion && Arrays.equals(seal, that.seal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tier, state, confirmed, Arrays.hashCode(googleId), Arrays.hashCode(googleIdFingerprint),
                Arrays.hashCode(email), Arrays.hashCode(emailFingerprint), Arrays.hashCode(username),
                Arrays.hashCode(usernameFingerprint), termsVersion, termsAcceptedAt, sealVersion, Arrays.hashCode(seal));
    }

    @Override
    public String toString() {
        return "AccountRow[id=" + id + ", tier=" + tier + ", state=" + state + ", confirmed=" + confirmed
                + ", sealVersion=" + sealVersion + ", googleId=" + length(googleId) + " bytes, email=" + length(email)
                + " bytes, username=" + length(username) + " bytes]";
    }

    /** A copy with another email address, its fingerprint, seal version and seal. */
    public AccountRow withEmail(byte[] newEmail, byte[] newEmailFingerprint, int newSealVersion, byte[] newSeal) {
        return new AccountRow(id, tier, state, confirmed, googleId, googleIdFingerprint, newEmail, newEmailFingerprint,
                username, usernameFingerprint, termsVersion, termsAcceptedAt, newSealVersion, newSeal);
    }

    private static int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }
}
