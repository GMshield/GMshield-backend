package io.github.gmshield.materials.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.modulith.NamedInterface;

/**
 * The record type keys a system has (Story 4.4 part B), as this module and {@code scope} read them: the
 * {@code knowledge} module implements it, as it does {@link ExtractedRecords}, because {@code knowledge} depends on
 * {@code materials} and {@code scope}, never the reverse. A record stores its type's key ({@code record.type}): a core
 * key, or the stable key of a type the GM approved in a system. Every call acts for the owner of the work
 * ({@code OwnerContext}) and joins the caller's transaction when there is one; another owner's system, an unknown one
 * or one being deleted has no key.
 */
@NamedInterface("service")
public interface RecordTypeKeys {

    /**
     * What a record type key looks like: a lower-case letter, then up to 31 lower-case letters, digits or underscores
     * (V40). Every check that took only core keys before Story 4.4 part B takes this pattern.
     */
    Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /** The core keys (Story 4.1), the same in every system, in their fixed order. */
    List<String> CORE = List.of("rule", "spell", "item", "creature", "character_sheet", "encounter", "dungeon", "note");
    /** How a log line names a key that is not core: an approved type's key is a slug of a name the GM approved. */
    String APPROVED = "approved";

    /** Whether {@code key} is a record type key's text ({@link #KEY}); false for null. */
    static boolean valid(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /**
     * A record type key as a log line may carry it: a core key as it is, any other key as {@value #APPROVED} (a line
     * names no library content), and {@code none} for null.
     */
    static String logged(String key) {
        if (key == null) {
            return "none";
        }
        return CORE.contains(key) ? key : APPROVED;
    }

    /**
     * A type a records step may extract (or, Story 5.3 part B, a type a draft's proposal may name): its key and its
     * name, as the model reads it. {@code toString} names a core key only.
     */
    @NamedInterface("service")
    record Named(String key, String name) {
        @Override
        public String toString() {
            return "Named[key=" + logged(key) + "]";
        }
    }

    /**
     * The types a records step of a material that counts under {@code systemIds} extracts, in order: the core types
     * rule, spell and item (by their stored English names), then every key approved in those systems, each once, in the
     * order of the systems and then of each system's types (oldest first), named by the first system that has it.
     */
    List<Named> extraction(Collection<UUID> systemIds);

    /**
     * The keys of {@link #extraction}, in the same order, with no name decrypted: what a records report is checked
     * against when it is recorded.
     */
    List<String> extractionKeys(Collection<UUID> systemIds);

    /**
     * Every record type key of the owner's system {@code systemId} (its core keys and its approved ones); empty for a
     * system that is unknown, another owner's or being deleted.
     */
    Set<String> keysOf(UUID systemId);

    /**
     * Every record type of the owner's system {@code systemId} with its name (Story 5.3 part B), the keys of
     * {@link #keysOf}: the core types first in their fixed order, by their stored English names, then the approved ones
     * oldest first, their names decrypted for a model's input only (data). Empty for a system that is unknown, another
     * owner's or being deleted.
     */
    List<Named> named(UUID systemId);
}
