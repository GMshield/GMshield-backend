package io.github.gmshield.accounts.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.shared.service.Ids;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the Redis stores write: hashed key names under gmshield:, and values without a token. */
class RedisValuesTest {

    private final byte[] hash = new byte[32];

    @Test
    void keysAreTheHexOfTheTokenHashUnderTheirPrefix() {
        hash[0] = (byte) 0xab;
        UUID account = Ids.newId();

        assertThat(RedisKeys.signIn(hash)).isEqualTo("gmshield:sign-in:ab" + "0".repeat(62));
        assertThat(RedisKeys.sessionToken(hash)).isEqualTo("gmshield:session:token:ab" + "0".repeat(62));
        assertThat(RedisKeys.session(account)).isEqualTo("gmshield:session:" + account);
        assertThat(RedisKeys.accountSessions(account)).isEqualTo("gmshield:session:account:" + account);
        assertThatThrownBy(() -> RedisKeys.signIn(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptsRoundTrip() {
        Instant expires = Instant.ofEpochSecond(1_800_000_000L);
        UUID account = Ids.newId();
        SignInAttempt started = SignInAttempt.started(expires);
        SignInAttempt identified = SignInAttempt.identified(expires, account, new byte[] {1, 2}, new byte[] {3});

        assertThat(RedisSignInAttemptStore.read(RedisSignInAttemptStore.write(started))).isEqualTo(started);
        assertThat(RedisSignInAttemptStore.read(RedisSignInAttemptStore.write(identified))).isEqualTo(identified);
        assertThat(RedisSignInAttemptStore.write(identified))
                .isEqualTo("{\"stage\":\"IDENTIFIED\",\"expiresAt\":1800000000,\"accountId\":\"" + account
                        + "\",\"googleId\":\"AQI=\",\"email\":\"Aw==\"}");
        assertThat(identified.toString()).doesNotContain("AQI=");
        assertThatThrownBy(() -> SignInAttempt.identified(expires, null, new byte[1], new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionsRoundTrip() {
        Instant created = Instant.ofEpochMilli(1_800_000_000_123L);
        UUID id = Ids.newId();
        UUID account = Ids.newId();
        hash[0] = 0x01;
        Session started = Session.started(id, account, 3, created, hash);
        byte[] next = new byte[32];
        next[31] = 0x02;
        Session renewed = started.seen(created.plusSeconds(30), true)
                .renewed(next, 4, created.plusSeconds(900), created.plusSeconds(960));

        assertThat(RedisSessionStore.read(RedisSessionStore.write(started))).contains(started);
        assertThat(RedisSessionStore.read(RedisSessionStore.write(renewed))).contains(renewed);
        // Ids, times, flags and hashes only: the JSON a session's key holds.
        assertThat(RedisSessionStore.write(renewed)).isEqualTo("{\"id\":\"" + id + "\",\"accountId\":\"" + account
                + "\",\"sealVersion\":4,\"createdAt\":1800000000123,\"lastSeenAt\":1800000900123,\"version\":3,"
                + "\"currentHash\":\"" + "0".repeat(62) + "02\",\"currentUsed\":false,"
                + "\"previousHash\":\"01" + "0".repeat(62) + "\",\"overlapUntil\":1800000960123}");
        assertThat(renewed.toString()).doesNotContain("0".repeat(62));
    }

    @Test
    void aStory19RecordOrADamagedValueIsNoSession() {
        // What Story 1.9 wrote under gmshield:session:<hex>: no id, no times of 1.10, no hashes.
        String story19 = "{\"accountId\":\"" + Ids.newId() + "\",\"sealVersion\":1,\"createdAt\":1800000000}";

        for (String stored : new String[] {story19, "", "not json", "[]", "{\"id\":\"x\"}", null,
                RedisSessionStore.write(Session.started(Ids.newId(), Ids.newId(), 1, Instant.EPOCH, hash))
                        .replace("\"currentHash\":\"", "\"currentHash\":\"zz")}) {
            assertThat(RedisSessionStore.read(stored)).as(String.valueOf(stored)).isEmpty();
        }
    }
}
