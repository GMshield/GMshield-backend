package io.github.gmshield.knowledge.service;

import io.github.gmshield.shared.service.ApiProblem;
import java.util.Map;

/**
 * The problems of the GM's edits of the ontology (Story 4.1). None carries content: the params are a field's limits
 * only.
 */
final class KnowledgeProblems {

    /** A core record type is renamed or removed: the core vocabulary is the same in every system. */
    static final ApiProblem CORE_RECORD_TYPE =
            ApiProblem.of(409, "ontology", "core_record_type", "Core record type");
    /** A record type's name outside its limits. */
    static final ApiProblem RECORD_TYPE_NAME_INVALID =
            ApiProblem.of(400, "ontology", "record_type_name_invalid", "Record type name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", Ontology.NAME_MAX));
    /** Another record type of the system, a core one included, has the same name (its unique form). */
    static final ApiProblem RECORD_TYPE_NAME_TAKEN =
            ApiProblem.of(409, "ontology", "record_type_name_taken", "Record type name taken");
    /** A record type that types records is removed (Story 4.2): its records would have no type. */
    static final ApiProblem RECORD_TYPE_IN_USE =
            ApiProblem.of(409, "ontology", "record_type_in_use", "Record type in use");
    /** The change names a version the item has left: someone changed it elsewhere. */
    static final ApiProblem STALE_VERSION =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version", "Stale version");

    private KnowledgeProblems() {
    }
}
