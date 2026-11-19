package io.github.gmshield.conversations.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language of a question (Story 2.4): {@code pt-BR}, {@code en}, or null when unsure. A count of each language's
 * common short words, with no library and no model: the language with more of them wins, a tie (none included) is
 * unsure. Words both languages write the same ({@code a}, {@code do}, {@code no}, {@code as}, {@code me}, {@code for}...)
 * count for neither.
 */
public final class Languages {

    public static final String PT_BR = "pt-BR";
    public static final String EN = "en";
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Set<String> PORTUGUESE = Set.of(
            "o", "os", "um", "uma", "uns", "umas", "de", "da", "das", "dos", "em", "na", "nas", "nos", "ao", "aos",
            "pelo", "pela", "pelos", "pelas", "num", "numa", "e", "ou", "mas", "que", "qual", "quais", "quanto",
            "quanta", "quantos", "quantas", "quem", "como", "onde", "quando", "porque", "por", "para", "pra", "com",
            "sem", "sobre", "entre", "até", "é", "são", "está", "estão", "ser", "foi", "tem", "têm", "ter", "há",
            "pode", "posso", "podem", "faz", "fazer", "isso", "isto", "esse", "essa", "este", "esta", "aquele",
            "aquela", "eu", "você", "vocês", "ele", "ela", "eles", "elas", "meu", "minha", "seu", "sua", "não", "sim",
            "mais", "muito", "muita", "também", "já", "cada", "outro", "outra", "acontece");
    private static final Set<String> ENGLISH = Set.of(
            "the", "an", "of", "to", "in", "on", "at", "by", "with", "from", "and", "or", "but", "what", "which", "who",
            "whom", "how", "when", "where", "why", "is", "are", "was", "were", "be", "been", "does", "did", "can",
            "could", "should", "would", "will", "has", "have", "had", "it", "its", "this", "that", "these", "those",
            "i", "you", "your", "my", "he", "she", "they", "their", "his", "her", "not", "yes", "much", "many", "more",
            "if", "than", "there", "any", "each", "other", "happens");

    private Languages() {
    }

    /** The question's language, or null when unsure. */
    public static String detect(String question) {
        if (question == null) {
            return null;
        }
        String text = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        int portuguese = 0;
        int english = 0;
        Matcher words = WORD.matcher(text);
        while (words.find()) {
            String word = words.group();
            portuguese += PORTUGUESE.contains(word) ? 1 : 0;
            english += ENGLISH.contains(word) ? 1 : 0;
        }
        if (portuguese == english) {
            return null;
        }
        return portuguese > english ? PT_BR : EN;
    }
}
