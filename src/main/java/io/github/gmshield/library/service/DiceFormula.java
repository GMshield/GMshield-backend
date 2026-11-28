package io.github.gmshield.library.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A dice formula, read by the frontend's rules (Story 6.4): a Java copy of {@code src/dice.ts}'s grammar (Story 6.3)
 * and of each die's range, so the backend stores a roll only when the browser could have made it. It never draws a die.
 *
 * <p>The grammar: a sum of terms joined by {@code +} or {@code -}, such as "1d8+2d6+3", "4d6-1" or "d20".
 * <ul>
 *   <li>A term is a dice group {@code [N]dS} (an omitted N means 1) or a whole-number modifier, in any order and number;
 *       the first term has no sign, and at least one dice group is needed.</li>
 *   <li>S is one of 4, 6, 8, 10, 12, 20 or 100; N runs from 1 to 100 and a modifier from 0 to 1000, with at most 100
 *       dice in all.</li>
 *   <li>Numbers have no leading zeros ("0" alone is a modifier).</li>
 *   <li>White space (JavaScript's {@code \s}) may stand only at either end and around a {@code +} or {@code -}, never
 *       inside a term.</li>
 *   <li>{@code D} reads as {@code d}. The normalised form has no white space and writes the count ("1d20").</li>
 * </ul>
 */
final class DiceFormula {

    /** The dice, as the roller shows them. */
    static final List<Integer> DICE = List.of(4, 6, 8, 10, 12, 20, 100);
    /** The most dice in one group, and in one formula. */
    static final int MAX_DICE = 100;
    /** The largest modifier. */
    static final int MAX_MODIFIER = 1000;

    /** A term: a dice group (sides above 0) or a modifier (sides 0, {@code value} its number), with its sign. */
    record Term(int sign, int count, int sides, int value) {

        boolean dice() {
            return sides > 0;
        }
    }

    private final String text;
    private final List<Term> terms;

    private DiceFormula(String text, List<Term> terms) {
        this.text = text;
        this.terms = List.copyOf(terms);
    }

    /** The normalised text: no white space, the count written. */
    String text() {
        return text;
    }

    List<Term> terms() {
        return terms;
    }

    /** How many dice the formula rolls, in all its groups. */
    int diceCount() {
        return terms.stream().filter(Term::dice).mapToInt(Term::count).sum();
    }

    /**
     * The total of a roll of this formula, when {@code results} are its dice in order: one result per die, group after
     * group, each within its die's range (1 to S). Empty when they are not.
     */
    Optional<Long> total(List<Integer> results) {
        if (results == null || results.size() != diceCount()) {
            return Optional.empty();
        }
        long total = 0;
        int next = 0;
        for (Term term : terms) {
            if (!term.dice()) {
                total += (long) term.sign() * term.value();
                continue;
            }
            for (int index = 0; index < term.count(); index++) {
                Integer result = results.get(next++);
                if (result == null || result < 1 || result > term.sides()) {
                    return Optional.empty();
                }
                total += (long) term.sign() * result;
            }
        }
        return Optional.of(total);
    }

    /** The formula {@code source} reads as, or empty when it does not follow the grammar. */
    static Optional<DiceFormula> parse(String source) {
        if (source == null) {
            return Optional.empty();
        }
        String input = source.replace('D', 'd');
        List<Term> terms = new ArrayList<>();
        int at = skipSpace(input, 0);
        do {
            int sign = 1;
            if (!terms.isEmpty()) {
                char operator = at < input.length() ? input.charAt(at) : '\0';
                if (operator != '+' && operator != '-') {
                    return Optional.empty();
                }
                sign = operator == '-' ? -1 : 1;
                at = skipSpace(input, at + 1);
            }
            int[] end = new int[1];
            Term term = readTerm(input, at, sign, end);
            if (term == null) {
                return Optional.empty();
            }
            terms.add(term);
            at = skipSpace(input, end[0]);
        } while (at < input.length());
        int dice = terms.stream().filter(Term::dice).mapToInt(Term::count).sum();
        if (dice == 0 || dice > MAX_DICE) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < terms.size(); index++) {
            Term term = terms.get(index);
            if (index > 0) {
                text.append(term.sign() == 1 ? '+' : '-');
            }
            text.append(term.dice() ? term.count() + "d" + term.sides() : Integer.toString(term.value()));
        }
        return Optional.of(new DiceFormula(text.toString(), terms));
    }

    /** The term written at {@code from} (no white space inside it), its end in {@code end[0]}; null when none reads. */
    private static Term readTerm(String input, int from, int sign, int[] end) {
        int digitsEnd = skipDigits(input, from);
        String digits = input.substring(from, digitsEnd);
        if (digitsEnd >= input.length() || input.charAt(digitsEnd) != 'd') {
            Integer value = readNumber(digits, 0, MAX_MODIFIER);
            end[0] = digitsEnd;
            return value == null ? null : new Term(sign, 0, 0, value);
        }
        int sidesEnd = skipDigits(input, digitsEnd + 1);
        Integer count = digits.isEmpty() ? Integer.valueOf(1) : readNumber(digits, 1, MAX_DICE);
        Integer sides = sidesOf(input.substring(digitsEnd + 1, sidesEnd));
        end[0] = sidesEnd;
        return count == null || sides == null ? null : new Term(sign, count, sides, 0);
    }

    /** A number written with no leading zero and within {@code min} and {@code max}, else null. */
    private static Integer readNumber(String digits, int min, int max) {
        if (digits.isEmpty() || digits.length() > Integer.toString(max).length()
                || (digits.length() > 1 && digits.charAt(0) == '0')) {
            return null;
        }
        int value = Integer.parseInt(digits);
        return value >= min && value <= max ? value : null;
    }

    private static Integer sidesOf(String digits) {
        return DICE.stream().filter(sides -> Integer.toString(sides).equals(digits)).findFirst().orElse(null);
    }

    private static int skipSpace(String input, int from) {
        int at = from;
        while (at < input.length() && isSpace(input.charAt(at))) {
            at++;
        }
        return at;
    }

    private static int skipDigits(String input, int from) {
        int at = from;
        while (at < input.length() && input.charAt(at) >= '0' && input.charAt(at) <= '9') {
            at++;
        }
        return at;
    }

    /**
     * JavaScript's {@code \s}: the tab, line feed, vertical tab, form feed, carriage return and space, the no-break
     * space, U+1680, U+2000 to U+200A, the line and paragraph separators, U+202F, U+205F, U+3000 and U+FEFF.
     */
    static boolean isSpace(char character) {
        return switch (character) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F',
                 '\u3000', '\uFEFF' -> true;
            default -> character >= '\u2000' && character <= '\u200A';
        };
    }

    @Override
    public String toString() {
        return "DiceFormula[" + text + "]";
    }
}
