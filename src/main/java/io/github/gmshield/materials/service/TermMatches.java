package io.github.gmshield.materials.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The names a question's terms match (Story 4.6, internal contract 1.23.0), as the {@code scope} module reads them to
 * resolve a question's terms: the {@code knowledge} module implements it, as it does {@link ExtractedRecords}, because
 * {@code knowledge} depends on {@code materials} and {@code scope}, never the reverse. Every call acts for the owner of
 * the work ({@code OwnerContext}) and joins the caller's transaction; another owner's system, versions and names match
 * nothing.
 *
 * <p>Each name given (a span of a question's words) is put in its unique form ({@code library.service.Library#
 * uniqueForm}: trimmed, NFC, lower case in the root locale) and compared by an equality on its keyed fingerprint, as a
 * stored name is: no text search, no plural or accent folding. Whether a version is {@code ready} and allowed is the
 * caller's check, never this port's. Nothing here logs a name.
 */
@NamedInterface("service")
public interface TermMatches {

    /**
     * What a system's ontology holds under some names: the keys of the record types named (a core type by its name in
     * pt-BR or en, singular or plural, or by its stored name; an approved type by its name), sorted, each once; and the
     * names of the terms matched by their name or by one of their aliases, each term's name followed by all its
     * aliases, terms oldest first, each name once. {@code toString} names counts only.
     */
    @NamedInterface("service")
    record Ontological(List<String> recordTypes, List<String> names) {

        /** Nothing matched. */
        public static final Ontological NONE = new Ontological(List.of(), List.of());

        public Ontological {
            recordTypes = List.copyOf(recordTypes);
            names = List.copyOf(names);
        }

        @Override
        public String toString() {
            return "Ontological[recordTypes=" + recordTypes.size() + ", names=" + names.size() + "]";
        }
    }

    /**
     * What the owner's system {@code systemId} holds under any of {@code names}, as {@link Ontological} says; nothing for
     * a system that is unknown, another owner's or being deleted, or for no name.
     */
    Ontological ontology(UUID systemId, Collection<String> names);

    /**
     * The owner's live records of these versions, of one of these type keys, whose name equals one of {@code names}
     * (an equality on the record name's keyed fingerprint, as {@link ExtractedRecords#named}), by id, at most
     * {@code limit}. A record stored before V37 has no fingerprint and never matches. Nothing is decrypted.
     */
    List<ExtractedRecords.Located> records(Collection<UUID> versionIds, Set<String> recordTypes,
                                           Collection<String> names, int limit);
}
