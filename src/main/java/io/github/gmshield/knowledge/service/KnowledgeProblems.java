package io.github.gmshield.knowledge.service;

import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.shared.service.ApiProblem;
import java.util.Map;

/**
 * The problems of the GM's edits of the ontology (Story 4.1) and of a record (Story 4.5). None carries content: the
 * params are a field's limits only, and a record field's position.
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
    /** A proposal approved under a name outside its limits (Story 4.4). */
    static final ApiProblem NAME_INVALID =
            ApiProblem.of(400, "ontology", "name_invalid", "Name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", Ontology.NAME_MAX));
    /**
     * A proposal approved or merged under a name the system already has (Story 4.4): a record type, a term, an alias or
     * a core type's name in pt-BR or en.
     */
    static final ApiProblem NAME_TAKEN =
            ApiProblem.of(409, "ontology", "name_taken", "Name taken");
    /** A merge into an item that is not of the proposal's kind, or not of its system, or unknown (Story 4.4). */
    static final ApiProblem MERGE_TARGET_INVALID =
            ApiProblem.of(400, "ontology", "merge_target_invalid", "Merge target invalid");
    /** A corrected record's name outside its limits (Story 4.5): blank, or longer than an extraction may give. */
    static final ApiProblem RECORD_NAME_INVALID =
            ApiProblem.of(400, "record", "name_invalid", "Record name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", ExtractedRecords.MAX_NAME));
    /** A correction with more fields than an extraction may give (Story 4.5). */
    static final ApiProblem RECORD_FIELDS_INVALID =
            ApiProblem.of(400, "record", "fields_invalid", "Record fields invalid")
                    .withParams(Map.<String, Object>of("max", ExtractedRecords.MAX_FIELDS));
    /** A corrected field's name outside its limits (Story 4.5); {@code index} names the field, from 0. */
    static final ApiProblem RECORD_FIELD_NAME_INVALID =
            ApiProblem.of(400, "record", "field_name_invalid", "Record field name invalid");
    /** A corrected field's value outside its limits (Story 4.5); {@code index} names the field, from 0. */
    static final ApiProblem RECORD_FIELD_VALUE_INVALID =
            ApiProblem.of(400, "record", "field_value_invalid", "Record field value invalid");
    /** A correction's record type key that is not one of the system's record types, core or approved (Story 4.5). */
    static final ApiProblem RECORD_TYPE_INVALID =
            ApiProblem.of(400, "record", "type_invalid", "Record type invalid");
    /** The change names a version the item has left: someone changed it elsewhere. */
    static final ApiProblem STALE_VERSION =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version", "Stale version");

    private KnowledgeProblems() {
    }
}
