package io.github.gmshield.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.audit.data.AuditRow;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.TraceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The audit log appends one row per entry, with safe details only, and has no other operation. */
class AuditLogTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00.123456789Z");

    private final InMemoryAuditStore store = new InMemoryAuditStore();
    private final AuditLog log = new AuditLog(store, clockProvider());

    private static org.springframework.beans.factory.ObjectProvider<Clock> clockProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        return beans.getBeanProvider(Clock.class);
    }

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void anEntryIsOneRowWithIdTimeActionAccountTraceAndDetails() {
        UUID account = Ids.newId();
        UUID session = Ids.newId();
        TraceContext.set("4bf92f3577b34da6a3ce929d0e0e4736");

        AuditEntry entry = log.append("session.token_reused", account,
                Map.of("sessionId", session, "reusedToken", "previous", "count", 2, "flag", true));

        AuditRow row = store.rows.getFirst();
        assertThat(store.rows).hasSize(1);
        assertThat(row.id()).isEqualTo(entry.id());
        assertThat(row.at()).isEqualTo(Instant.parse("2026-11-16T12:00:00.123456Z"));
        assertThat(row.action()).isEqualTo("session.token_reused");
        assertThat(row.accountId()).isEqualTo(account);
        assertThat(row.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(row.details()).isEqualTo("{\"count\":2,\"flag\":true,\"reusedToken\":\"previous\",\"sessionId\":\""
                + session + "\"}");
    }

    @Test
    void detailsThatCouldHoldATokenAHashAnAddressOrTextAreRefusedBeforeAnyWrite() {
        Object[] unsafe = {"A".repeat(43), "ab".repeat(32), "gm@example.test", "Mestre Ana", "UPPER", "",
                new byte[] {1}, 1.5, null, Map.of("nested", "x"), "x".repeat(41)};
        for (Object value : unsafe) {
            Map<String, Object> details = new HashMap<>();
            details.put("value", value);
            assertThatThrownBy(() -> log.append("session.token_reused", Ids.newId(), details))
                    .as(String.valueOf(value)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("example.test");
        }
        for (String name : new String[] {"Token", "with space", "a".repeat(41), ""}) {
            assertThatThrownBy(() -> log.append("session.token_reused", null, Map.of(name, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String action : new String[] {"reused", "Session.reused", "session.", "session..x", "session.re used"}) {
            assertThatThrownBy(() -> log.append(action, null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, Object> many = new HashMap<>();
        for (int i = 0; i < 17; i++) {
            many.put("d" + i, i);
        }
        assertThatThrownBy(() -> log.append("session.token_reused", null, many)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void outsideARequestTheTraceIsTheAbsentOne() {
        log.append("session.token_reused", null, null);

        assertThat(store.rows.getFirst().traceId()).isEqualTo(TraceContext.NONE);
        assertThat(store.rows.getFirst().details()).isEqualTo("{}");
    }

    @Test
    void theLogOffersAppendingOnly() {
        assertThat(java.util.Arrays.stream(AuditLog.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)).containsExactly("append");
        assertThat(java.util.Arrays.stream(io.github.gmshield.audit.data.AuditStore.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).containsExactly("append");
    }
}
