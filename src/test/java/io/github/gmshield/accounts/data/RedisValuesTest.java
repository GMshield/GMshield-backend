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
        assertThat(RedisKeys.session(hash)).isEqualTo("gmshield:session:ab" + "0".repeat(62));
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
        Session session = new Session(Ids.newId(), 3, Instant.ofEpochSecond(1_800_000_000L));

        assertThat(RedisSessionStore.read(RedisSessionStore.write(session))).isEqualTo(session);
    }
}
