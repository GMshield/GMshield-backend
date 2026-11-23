package io.github.gmshield.conversations.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 3.12: a saved answer's excerpt, its Markdown removed and cut at a word boundary. */
class MarkdownTest {

    @Test
    void headingsEmphasisListsQuotesAndLinksBecomePlainText() {
        assertThat(Markdown.plain("## Descanso curto\n\n**Uma hora**, e *gaste* dados de __vida__ e ~~nada~~."))
                .isEqualTo("Descanso curto Uma hora, e gaste dados de vida e nada.");
        assertThat(Markdown.plain("- primeiro\n* segundo\n+ terceiro\n1. quarto\n2) quinto"))
                .isEqualTo("primeiro segundo terceiro quarto quinto");
        assertThat(Markdown.plain("> citado\n>> mais fundo")).isEqualTo("citado mais fundo");
        assertThat(Markdown.plain("Veja [o livro](https://example.test/a) e ![a figura](f.png)."))
                .isEqualTo("Veja o livro e a figura.");
        assertThat(Markdown.plain("Use `rolar 1d20` agora")).isEqualTo("Use rolar 1d20 agora");
    }

    @Test
    void fencesRulesAndTableSeparatorsGoButTheCodeAndCellsStay() {
        assertThat(Markdown.plain("Antes\n```js\nconst a = 1;\n```\n---\n***\nDepois"))
                .isEqualTo("Antes const a = 1; Depois");
        assertThat(Markdown.plain("| Magia | Dano |\n|---|:---:|\n| Fire Bolt | 1d10 |"))
                .isEqualTo("Magia   Dano Fire Bolt   1d10");
    }

    @Test
    void marksInsideAWordStayAndPlainTextIsKept() {
        assertThat(Markdown.plain("snake_case e 2*3")).isEqualTo("snake_case e 2*3");
        assertThat(Markdown.plain("#hashtag sem espaço")).isEqualTo("#hashtag sem espaço");
        assertThat(Markdown.plain("")).isEmpty();
        assertThat(Markdown.plain(null)).isEmpty();
    }

    @Test
    void theExcerptFlattensWhiteSpaceAndIsCutAt280CodePointsAtAWordBoundary() {
        assertThat(Conversations.excerpt("# Título\n\nUm   texto\n\n\tcurto.")).isEqualTo("Título Um texto curto.");
        String cut = Conversations.excerpt("**palavra** ".repeat(60));
        assertThat(cut.codePointCount(0, cut.length())).isLessThanOrEqualTo(280);
        assertThat(cut).endsWith("palavra").doesNotContain("*");
        assertThat(cut.split(" ")).allMatch("palavra"::equals);
        String emoji = Conversations.excerpt("😀".repeat(300));
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(280);
    }
}
