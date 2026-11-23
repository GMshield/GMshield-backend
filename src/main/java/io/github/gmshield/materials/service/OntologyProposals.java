package io.github.gmshield.materials.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The record types and terms a version's records steps propose (Story 4.4), as this module hands them over: the
 * {@code knowledge} module implements it, as it does {@link ExtractedRecords}, because {@code knowledge} depends on
 * {@code materials} and never the reverse. Every call acts for the owner of the work ({@code OwnerContext}) and joins
 * the caller's transaction, which must exist. A proposal joins nothing by itself: only the GM's approval, through the
 * public API, makes it a record type or a term.
 *
 * <p>The quotes reach this port checked: each example and definition was found, whitespace collapsed, in the text of a
 * passage of the version it cites. Whatever clears a version's records (a restart, a reprocess, a passages report, a
 * discard, a refused upload) clears its pending proposals too ({@link #clearPending}), and a material's deletion
 * {@link #removeMaterial} before its row goes.
 */
@NamedInterface("service")
public interface OntologyProposals {

    /** A record type proposal's kind. */
    String RECORD_TYPE = "record_type";
    /** A term proposal's kind. */
    String TERM = "term";

    /** An example of a proposal: a quote of at most 200 code points and the page of the passage it was found in. */
    @NamedInterface("service")
    record Example(int page, String quote) {
        @Override
        public String toString() {
            return "Example[page=" + page + "]";
        }
    }

    /**
     * One proposal of a records step: its kind ({@value #RECORD_TYPE} or {@value #TERM}), its name, its definition
     * (a term's, quoted; null when there is none) and its checked examples (1 to 3). {@code toString} names no content.
     */
    @NamedInterface("service")
    record Proposed(String kind, String name, String definition, List<Example> examples) {
        public Proposed {
            examples = List.copyOf(examples);
        }

        @Override
        public String toString() {
            return "Proposed[kind=" + kind + ", examples=" + examples.size() + "]";
        }
    }

    /** What {@link #store} did: proposals stored, repeats that added examples, and proposals dropped. */
    @NamedInterface("service")
    record Outcome(int stored, int extended, int dropped) {
    }

    /**
     * Stores {@code proposals} of the version {@code versionId} of the material {@code materialId} for each system of
     * {@code systemIds} (the systems the material counts under): a proposal whose name the system already has (a type,
     * a term, an alias, a core name in pt-BR or en) is dropped, and so is one already present for the same system,
     * material and name, whatever its state; a repeat within the same version adds its examples, up to three.
     */
    Outcome store(UUID materialId, UUID versionId, Collection<UUID> systemIds, List<Proposed> proposals);

    /**
     * The version's pending proposals go, and its decided ones no longer name it; answers how many went. Safe to
     * repeat.
     */
    int clearPending(UUID versionId);

    /**
     * The material's proposals that were not approved go, and the approved ones no longer name it (their origin keeps
     * the proposal, without a material); answers how many went. Safe to repeat.
     */
    int removeMaterial(UUID materialId);
}
