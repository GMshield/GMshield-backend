package io.github.gmshield.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.data.JobRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules of the jobs module that need no Spring: the ingestion round robin, the settings, the grant's shape. */
class JobRulesTest {

    private static final Instant T = Instant.parse("2026-11-16T12:00:00Z");
    private static final UUID LOW = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID MID = UUID.fromString("7fffffff-0000-7000-8000-000000000001");
    /** Above MID only in unsigned order: Java's signed UUID order would put it first. */
    private static final UUID HIGH = UUID.fromString("ffffffff-0000-7000-8000-000000000001");

    @Test
    void withNoOwnerOfferedBeforeTheOldestJobGoesFirst() {
        JobRow older = waiting(HIGH, T);
        JobRow newer = waiting(LOW, T.plusSeconds(1));

        assertThat(JobOffers.nextInTurn(List.of(newer, older), Optional.empty())).contains(older);
        assertThat(JobOffers.nextInTurn(List.of(), Optional.empty())).isEmpty();
    }

    @Test
    void theNextOwnerAfterTheLastOneOfferedGoesNextWrappingAround() {
        JobRow low = waiting(LOW, T.plusSeconds(5));
        JobRow mid = waiting(MID, T.plusSeconds(1));
        JobRow high = waiting(HIGH, T);
        List<JobRow> all = List.of(high, low, mid);

        assertThat(JobOffers.nextInTurn(all, Optional.of(LOW))).contains(mid);
        assertThat(JobOffers.nextInTurn(all, Optional.of(MID))).contains(high);
        assertThat(JobOffers.nextInTurn(all, Optional.of(HIGH))).contains(low);
        // The owner offered last, alone with waiting jobs, goes again.
        assertThat(JobOffers.nextInTurn(List.of(low), Optional.of(LOW))).contains(low);
    }

    @Test
    void settingsOutOfOrderStopTheStartNamingOnlyTheSettings() {
        Duration lapse = Duration.ofSeconds(90);
        Duration renew = Duration.ofSeconds(30);
        Duration reoffer = Duration.ofMinutes(2);
        Duration sweep = Duration.ofSeconds(10);
        JobSettings settings = new JobSettings(lapse, renew, 3, reoffer, sweep);
        assertThat(settings.maxAttempts()).isEqualTo(3);

        for (Runnable bad : List.<Runnable>of(
                () -> new JobSettings(lapse, lapse, 3, reoffer, sweep),
                () -> new JobSettings(lapse, renew, 0, reoffer, sweep),
                () -> new JobSettings(lapse, renew, 3, Duration.ofHours(1), sweep),
                () -> new JobSettings(lapse, renew, 3, reoffer, Duration.ZERO),
                () -> new JobSettings(lapse, Duration.ofSeconds(-1), 3, reoffer, sweep))) {
            assertThatThrownBy(bad::run).hasMessageStartingWith("setting.invalid: GMSHIELD_JOB_");
        }
    }

    @Test
    void outputLimitsThatAreNotPositiveOrPassTheClaimsStopTheStart() {
        org.springframework.util.unit.DataSize three = org.springframework.util.unit.DataSize.ofGigabytes(3);
        org.springframework.util.unit.DataSize eight = org.springframework.util.unit.DataSize.ofGigabytes(8);
        JobFileSettings settings = new JobFileSettings(three, eight);
        assertThat(settings.outputMaxBytes()).isEqualTo(3L * 1024 * 1024 * 1024);
        assertThat(settings.claimMaxBytes()).isEqualTo(8L * 1024 * 1024 * 1024);
        for (Runnable bad : List.<Runnable>of(
                () -> new JobFileSettings(org.springframework.util.unit.DataSize.ofBytes(0), eight),
                () -> new JobFileSettings(eight, three),
                () -> new JobFileSettings(null, eight))) {
            assertThatThrownBy(bad::run).hasMessageStartingWith("setting.invalid: GMSHIELD_JOB_OUTPUT_MAX_BYTES");
        }
    }

    @Test
    void aGrantIs32RandomBytesInBase64Url() {
        String first = JobClaims.newGrant();
        String second = JobClaims.newGrant();

        assertThat(first).matches(JobClaims.GRANT).isNotEqualTo(second);
        assertThat(java.util.Base64.getUrlDecoder().decode(first)).hasSize(32);
    }

    @Test
    void anOutcomeCodeIsCategoryDotReasonInLowerCase() {
        for (String good : new String[] {"probe.failed", "ingestion.pipeline_changed", "a.b.c"}) {
            assertThat(JobClaims.CODE.matcher(good).matches()).as(good).isTrue();
        }
        for (String bad : new String[] {"failed", "Probe.failed", "probe.", ".probe", "probe failed", "a".repeat(40) + ".b"}) {
            assertThat(JobClaims.CODE.matcher(bad).matches()).as(bad).isFalse();
        }
    }

    @Test
    void anOutcomeCodeFitsTheColumn() {
        assertThat(JobClaims.MAX_CODE_LENGTH).isEqualTo(64);
    }

    @Test
    void theGrantComparisonLooksAtEveryByte() {
        byte[] expected = {1, 2, 3};
        assertThat(JobClaims.sameBytes(expected, new byte[] {1, 2, 3})).isTrue();
        assertThat(JobClaims.sameBytes(expected, new byte[] {1, 2, 4})).isFalse();
        assertThat(JobClaims.sameBytes(expected, new byte[] {1, 2})).isFalse();
        assertThat(JobClaims.sameBytes(expected, null)).isFalse();
        assertThat(JobClaims.sameBytes(null, expected)).isFalse();
    }

    @Test
    void lanesHaveTheirStreams() {
        assertThat(Lane.INTERACTIVE.stream()).isEqualTo("gmshield:work:interactive");
        assertThat(Lane.INGESTION.stream()).isEqualTo("gmshield:work:ingestion");
        assertThat(Lane.of("ingestion")).contains(Lane.INGESTION);
        assertThat(Lane.of("other")).isEmpty();
    }

    private static JobRow waiting(UUID owner, Instant createdAt) {
        return new JobRow(UUID.randomUUID(), owner, "probe", "ingestion", "waiting", 0, "0".repeat(31) + "1", null, null,
                null, null, null, null, createdAt, createdAt, null, null, null);
    }

    @Test
    void aStatedRecipeIsPublishedOnceUntilANewOneAndAFailedPublicationIsPublishedAgainAtTheNextStatement() {
        List<Object> published = new java.util.ArrayList<>();
        boolean[] failing = {true};
        JobClaims claims = new JobClaims(null, null, null, null, null, null, null, event -> {
            if (failing[0]) {
                throw new IllegalStateException("listener down");
            }
            published.add(event);
        }, null, null);

        // The listener throws: the statement still succeeds, and the recipe is not recorded as stated.
        claims.stateRecipe("1");
        assertThat(published).isEmpty();
        failing[0] = false;
        claims.stateRecipe("1");
        claims.stateRecipe("1");
        claims.stateRecipe("2");
        claims.stateRecipe("2");
        assertThat(published).containsExactly(new IndexRecipeStated("1"), new IndexRecipeStated("2"));

        for (String bad : new String[] {null, "", "two words", "x".repeat(33), "é"}) {
            assertThatThrownBy(() -> claims.stateRecipe(bad)).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(io.github.gmshield.shared.service.ApiException.class,
                            e -> assertThat(e.problem().status()).isEqualTo(400));
        }
        assertThat(published).hasSize(2);
    }
}
