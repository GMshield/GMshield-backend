package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 6.4: the backend reads a formula exactly as the frontend's {@code src/dice.ts} does (Story 6.3). The formulas
 * read and refused are {@code test/dice.test.ts}'s matrix, and a roll's results must be its dice in order, each within
 * its die's range.
 */
class DiceFormulaTest {

    @ParameterizedTest(name = "reads {0} as {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "d20|1d20", "2d6+3|2d6+3", "1d8-2|1d8-2", "\" 2D6 + 3 \"|2d6+3", "1d8+2d6-1|1d8+2d6-1", "4d6-1|4d6-1",
        "1d8+2d6+3|1d8+2d6+3", "d100|1d100", "100d6|100d6", "1d6+1000|1d6+1000", "1d6+0|1d6+0", "3+1d6|3+1d6",
        "2d6-1d4|2d6-1d4", "60d6+40d4|60d6+40d4", "1d6+2+3|1d6+2+3"})
    void readsWhatTheFrontendReads(String text, String normalised) {
        assertThat(DiceFormula.parse(text)).hasValueSatisfying(formula -> assertThat(formula.text()).isEqualTo(normalised));
    }

    @Test
    void readsTabsAndLineBreaksAtEitherEndAndAroundASign() {
        assertThat(DiceFormula.parse("\t1d12\n").orElseThrow().text()).isEqualTo("1d12");
        assertThat(DiceFormula.parse("\t2d6\n-\t1 ").orElseThrow().text()).isEqualTo("2d6-1");
        // JavaScript's \s includes the no-break space and U+FEFF, not U+0085.
        assertThat(DiceFormula.parse("\u00A02d6\uFEFF+\u20033\u3000").orElseThrow().text()).isEqualTo("2d6+3");
        assertThat(DiceFormula.parse("2d6\u0085")).isEmpty();
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {"1d7", "d3", "abc", "", "   ", "60d6+41d4", "101d6", "0d6", "+2d6", "-2d6", "2d6+", "3",
        "3+4", "2d", "d", "2d6++3", "2d6+3d", "2d6*3", "2d6+1001", "02d6", "2d06", "2d6+03", "1d20d6", "2x6", "1e2d6",
        "2d6 3", "\uFF11d6", "1d10 0", "1 2d6", "2d6+1 0", "1 d12", "1d 12", "\t1 d 12\n", "2 d6", "2d 6+3"})
    void refusesWhatTheFrontendRefuses(String text) {
        assertThat(DiceFormula.parse(text)).isEmpty();
    }

    @Test
    void refusesNoFormula() {
        assertThat(DiceFormula.parse(null)).isEmpty();
    }

    @Test
    void keepsEachTermWithItsSignAndKind() {
        assertThat(DiceFormula.parse("1d8+2d6-1").orElseThrow().terms()).containsExactly(
                new DiceFormula.Term(1, 1, 8, 0), new DiceFormula.Term(1, 2, 6, 0), new DiceFormula.Term(-1, 0, 0, 1));
    }

    @Test
    void theResultsAreTheDiceInOrderEachWithinItsRangeAndTheTotalIsComputed() {
        assertThat(total("2d6+3", 4, 5)).hasValue(12L);
        assertThat(total("1d8-2", 1)).hasValue(-1L);
        assertThat(total("1d8+2d6-1", 8, 6, 1)).hasValue(14L);
        assertThat(total("2d6-1d4", 6, 6, 4)).hasValue(8L);
        assertThat(total("3+1d6", 6)).hasValue(9L);
        // The spec's rows: too high, too many, too few.
        assertThat(total("1d6", 7)).isEmpty();
        assertThat(total("1d6", 3, 4)).isEmpty();
        assertThat(total("2d6-1d4", 6, 6)).isEmpty();
        // The order matters: the d4 group comes second.
        assertThat(total("1d4+1d20", 20, 4)).isEmpty();
        assertThat(total("1d4+1d20", 4, 20)).hasValue(24L);
        assertThat(total("1d6", 0)).isEmpty();
        assertThat(total("1d6")).isEmpty();
        assertThat(DiceFormula.parse("1d6").orElseThrow().total(null)).isEmpty();
        assertThat(DiceFormula.parse("2d6").orElseThrow().total(Arrays.asList(1, null))).isEmpty();
    }

    @Test
    void everyFaceOfEveryDieIsAcceptedAndNothingOutsideItsRange() {
        for (int sides : DiceFormula.DICE) {
            DiceFormula formula = DiceFormula.parse("1d" + sides).orElseThrow();
            IntStream.rangeClosed(1, sides).forEach(face -> assertThat(formula.total(List.of(face))).hasValue((long) face));
            assertThat(formula.total(List.of(sides + 1))).isEmpty();
            assertThat(formula.total(List.of(-1))).isEmpty();
        }
    }

    @Test
    void aHundredDiceAreAccepted() {
        assertThat(DiceFormula.parse("100d100").orElseThrow().total(IntStream.range(0, 100).boxed().map(i -> 100)
                .toList())).hasValue(10_000L);
    }

    private static java.util.Optional<Long> total(String formula, Integer... results) {
        return DiceFormula.parse(formula).orElseThrow().total(List.of(results));
    }
}
