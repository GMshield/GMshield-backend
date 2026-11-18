package io.github.gmshield.jobs.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The time of every job decision: the application's clock (a test may replace it), to the microsecond as stored. */
@Component
class JobClock {

    private final ObjectProvider<Clock> clock;

    JobClock(ObjectProvider<Clock> clock) {
        this.clock = clock;
    }

    Instant now() {
        return clock.getIfAvailable(Clock::systemUTC).instant().truncatedTo(ChronoUnit.MICROS);
    }
}
