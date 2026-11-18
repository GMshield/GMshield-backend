package io.github.gmshield.accounts.service;

import java.util.UUID;

/**
 * An account as loaded, after its seal was checked, with its encrypted fields decrypted. Its
 * {@code toString} shows ids, tier and state only, never the username, the email address or the Google id.
 */
public record Account(UUID id, String tier, String state, boolean confirmed, String googleId, String email,
                      String username, int sealVersion) {

    public static final String TIER_ORDINARY = "ordinary";
    public static final String STATE_ACTIVE = "active";

    public boolean active() {
        return STATE_ACTIVE.equals(state);
    }

    @Override
    public String toString() {
        return "Account[id=" + id + ", tier=" + tier + ", state=" + state + ", confirmed=" + confirmed
                + ", sealVersion=" + sealVersion + "]";
    }
}
