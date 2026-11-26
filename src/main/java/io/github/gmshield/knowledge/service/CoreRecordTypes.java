package io.github.gmshield.knowledge.service;

import io.github.gmshield.library.service.Library;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The core vocabulary (Story 4.1): the eight record types every system has, the same in every system, which the GM can
 * neither rename nor remove. Each is identified by its fixed plaintext key (stored in {@code record_type.core}); its
 * stored name is the key in English ({@code character_sheet}: "character sheet"), so a type the GM approves later is
 * checked against it for uniqueness. The frontend never shows the stored name of a core type, only its own translation
 * of the key. {@link #KEYS} is the fixed order in which every list shows them.
 */
public final class CoreRecordTypes {

    /** The core keys and their stored (English) names, in the fixed order. */
    private static final Map<String, String> NAMES = names();
    /** The core keys, in the fixed order. */
    public static final List<String> KEYS = List.copyOf(NAMES.keySet());
    /**
     * The core types' names as the frontend shows them in pt-BR (Story 4.4): with the stored English names, they are part
     * of each system's one name space, so no approved type or term takes one ("Magia" beside the core spell). They
     * change nothing in the core vocabulary.
     */
    static final List<String> NAMES_PT_BR = List.of("Regra", "Magia", "Item", "Criatura", "Ficha de personagem",
            "Encontro", "Masmorra", "Nota");

    /**
     * The core types' names as a question may write them (Story 4.6), by key in {@link #KEYS}' order: the stored English
     * name and {@link #NAMES_PT_BR}'s, each also in its plural ("Quais magias…"). A fixed list of the eight types' own
     * words, not a folding: no other name is put in the plural.
     */
    private static final Map<String, List<String>> NAMES_ASKED = asked();
    /** The unique form ({@link Library#uniqueForm}) of each name of {@link #NAMES_ASKED}, and its key. */
    private static final Map<String, String> KEYS_BY_ASKED_FORM = keysByAskedForm();

    private CoreRecordTypes() {
    }

    /**
     * The core key a question's span names (Story 4.6): {@code form} is a span in its unique form
     * ({@link Library#uniqueForm}), compared exactly with the core names in pt-BR and en, singular and plural; empty for
     * any other text.
     */
    public static Optional<String> keyAsked(String form) {
        return form == null ? Optional.empty() : Optional.ofNullable(KEYS_BY_ASKED_FORM.get(form));
    }

    /** The stored name of the core type {@code key}: the key in English, with spaces. */
    public static String name(String key) {
        String name = NAMES.get(key);
        if (name == null) {
            throw new IllegalArgumentException("knowledge.core_key_unknown");
        }
        return name;
    }

    /** The core types' names in both languages (Story 4.4): the stored English ones and {@link #NAMES_PT_BR}. */
    static List<String> namesInEveryLanguage() {
        List<String> all = new java.util.ArrayList<>(NAMES.values());
        all.addAll(NAMES_PT_BR);
        return List.copyOf(all);
    }

    private static Map<String, List<String>> asked() {
        Map<String, List<String>> asked = new LinkedHashMap<>();
        asked.put("rule", List.of("rule", "rules", "Regra", "Regras"));
        asked.put("spell", List.of("spell", "spells", "Magia", "Magias"));
        asked.put("item", List.of("item", "items", "Item", "Itens"));
        asked.put("creature", List.of("creature", "creatures", "Criatura", "Criaturas"));
        asked.put("character_sheet", List.of("character sheet", "character sheets", "Ficha de personagem",
                "Fichas de personagem"));
        asked.put("encounter", List.of("encounter", "encounters", "Encontro", "Encontros"));
        asked.put("dungeon", List.of("dungeon", "dungeons", "Masmorra", "Masmorras"));
        asked.put("note", List.of("note", "notes", "Nota", "Notas"));
        return java.util.Collections.unmodifiableMap(asked);
    }

    private static Map<String, String> keysByAskedForm() {
        Map<String, String> keys = new LinkedHashMap<>();
        NAMES_ASKED.forEach((key, names) -> names.forEach(name -> keys.put(Library.uniqueForm(name), key)));
        return Map.copyOf(keys);
    }

    private static Map<String, String> names() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String key : List.of("rule", "spell", "item", "creature", "character_sheet", "encounter", "dungeon", "note")) {
            names.put(key, key.replace('_', ' '));
        }
        return java.util.Collections.unmodifiableMap(names);
    }
}
