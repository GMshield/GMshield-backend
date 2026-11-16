package io.github.gmshield.audit.service;

import io.github.gmshield.audit.data.AuditRow;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.TraceContext;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends to the audit log. There is no way here to change or remove an entry.
 *
 * <p>What a detail may hold is narrow on purpose, so that a token, a hash, an email address or text can
 * never slip in: a {@link UUID}, a whole number, a flag, or a short lower-case code
 * ({@code [a-z0-9_.-]}, at most 40 characters: a 43-character token or a 64-character hash does not fit,
 * nor does an address with {@code @}). Anything else is refused with an {@link IllegalArgumentException}
 * before any write.
 */
@Service
public class AuditLog {

    static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    static final Pattern DETAIL_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    static final Pattern CODE = Pattern.compile("[a-z0-9_.-]{1,40}");
    static final int MAX_DETAILS = 16;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final AuditStore store;
    private final Clock clock;

    AuditLog(AuditStore store, ObjectProvider<Clock> clock) {
        this.store = store;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Appends one entry for the current request's trace and gives it back. */
    public AuditEntry append(String action, UUID accountId, Map<String, Object> details) {
        if (action == null || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("an audit action is area.event in lower case");
        }
        Map<String, Object> checked = checked(details == null ? Map.of() : details);
        AuditEntry entry = new AuditEntry(Ids.newId(), clock.instant().truncatedTo(ChronoUnit.MICROS), action, accountId,
                TraceContext.current(), checked);
        store.append(new AuditRow(entry.id(), entry.at(), entry.action(), entry.accountId(), entry.traceId(),
                JSON.writeValueAsString(new TreeMap<>(checked))));
        log.atInfo().addKeyValue("action", action).addKeyValue("accountId", accountId).addKeyValue("auditId", entry.id())
                .log("audit.appended");
        return entry;
    }

    private static Map<String, Object> checked(Map<String, Object> details) {
        if (details.size() > MAX_DETAILS) {
            throw new IllegalArgumentException("at most " + MAX_DETAILS + " audit details");
        }
        Map<String, Object> checked = new TreeMap<>();
        for (Map.Entry<String, Object> detail : details.entrySet()) {
            if (detail.getKey() == null || !DETAIL_NAME.matcher(detail.getKey()).matches()) {
                throw new IllegalArgumentException("an audit detail name is a short camelCase word");
            }
            checked.put(detail.getKey(), safeValue(detail.getValue()));
        }
        return checked;
    }

    /** The value as written, or a refusal that never quotes it. */
    private static Object safeValue(Object value) {
        return switch (value) {
            case UUID id -> id.toString();
            case Boolean flag -> flag;
            case Integer number -> number;
            case Long number -> number;
            case String code when CODE.matcher(code).matches() -> code;
            case null, default -> throw new IllegalArgumentException(
                    "an audit detail is an id, a whole number, a flag or a short lower-case code");
        };
    }
}
