package io.github.gmshield.shared.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void validHeaderGivesItsTraceId() {
        assertThat(TraceContext.fromTraceparent("00-" + TRACE_ID + "-00f067aa0ba902b7-01")).contains(TRACE_ID);
        assertThat(TraceContext.fromTraceparent(" 00-" + TRACE_ID + "-00f067aa0ba902b7-00 ")).contains(TRACE_ID);
        // A later version may add fields after the flags.
        assertThat(TraceContext.fromTraceparent("01-" + TRACE_ID + "-00f067aa0ba902b7-01-extra")).contains(TRACE_ID);
    }

    @Test
    void malformedHeaderGivesNothing() {
        assertThat(TraceContext.fromTraceparent(null)).isEmpty();
        assertThat(TraceContext.fromTraceparent("")).isEmpty();
        assertThat(TraceContext.fromTraceparent(TRACE_ID)).isEmpty();
        assertThat(TraceContext.fromTraceparent("00-" + TRACE_ID + "-00f067aa0ba902b7")).isEmpty();
        assertThat(TraceContext.fromTraceparent("00-" + TRACE_ID + "-00f067aa0ba902b7-01-extra")).isEmpty();
        assertThat(TraceContext.fromTraceparent("ff-" + TRACE_ID + "-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.fromTraceparent("00-" + TRACE_ID.toUpperCase() + "-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.fromTraceparent("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.fromTraceparent("00-" + TRACE_ID + "-" + "0".repeat(16) + "-01")).isEmpty();
    }

    @Test
    void newTraceIdIs32LowercaseHexDigits() {
        assertThat(TraceContext.newTraceId()).matches("[0-9a-f]{32}").isNotEqualTo(TraceContext.newTraceId());
    }
}
