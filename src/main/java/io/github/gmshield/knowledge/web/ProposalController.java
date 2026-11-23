package io.github.gmshield.knowledge.web;

import io.github.gmshield.knowledge.service.Proposals;
import io.github.gmshield.knowledge.service.Terms;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ontology proposals of a system and of a material, the GM's decisions on them, and a system's approved terms
 * (Story 4.4). The owner is the session's account, set by the request edge ({@code accounts.web}) before any of these
 * runs; nothing here takes an owner from the request. Another owner's id, an unknown id, a malformed id and an item of a
 * system being deleted are the same {@code request.not_found} (404). A decision names the proposal's version
 * ({@code request.stale_version}, 409, when it moved or the proposal was decided meanwhile). A malformed body, limit or
 * offset is {@code request.invalid} (400). Every answer is {@code no-store}.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "ontology")
class ProposalController {

    static final String SYSTEM_PROPOSALS = "/api/v1/systems/{id}/proposals";
    static final String MATERIAL_PROPOSALS = "/api/v1/materials/{id}/proposals";
    static final String SYSTEM_TERMS = "/api/v1/systems/{id}/terms";
    static final String APPROVAL = "/api/v1/proposals/{id}/approval";
    static final String MERGE = "/api/v1/proposals/{id}/merge";
    static final String REJECTION = "/api/v1/proposals/{id}/rejection";
    /** No item has this id: a malformed merge target. */
    private static final UUID NO_TARGET = new UUID(0, 0);
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String DECISION_SUMMARY = " request.stale_version (409) when the proposal has left that"
            + " version or was decided meanwhile; request.invalid (400) without a version or for a malformed body;"
            + " request.not_found (404) for any other id, one of a system being deleted included; nothing changes then;";

    private final Proposals proposals;
    private final Terms terms;

    ProposalController(Proposals proposals, Terms terms) {
        this.proposals = proposals;
        this.terms = terms;
    }

    @Schema(name = "ProposalExample", requiredProperties = {"page", "quote"})
    record ExampleView(
            @Schema(description = "The page of the passage the quote was found in", format = "int32", minimum = "1",
                    example = "42") int page,
            @Schema(description = "The quote, verbatim from that passage, at most 200 code points",
                    example = "Talentos são habilidades especiais") String quote) {

        @Override
        public String toString() {
            return "ExampleView[page=" + page + "]";
        }
    }

    @Schema(name = "Proposal", requiredProperties = {"id", "systemId", "materialId", "materialName", "kind", "name",
        "definition", "examples", "state", "version"})
    record ProposalView(
            @Schema(description = "The proposal's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000030")
            String id,
            @Schema(description = "The system it was proposed to: each system the material counts under decides on its"
                    + " own", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000001") String systemId,
            @Schema(description = "The material that proposed it; null once that material was deleted", format = "uuid",
                    types = {"string", "null"}, example = "0190a0b0-0000-7000-8000-000000000004") String materialId,
            @Schema(description = "That material's name; null when materialId is null", types = {"string", "null"},
                    example = "Livro do Jogador") String materialName,
            @Schema(description = "What it proposes: a record type or a term", allowableValues = {"record_type", "term"},
                    example = "record_type") String kind,
            @Schema(description = "The proposed name", example = "Talento") String name,
            @Schema(description = "A term's definition, quoted from the material; for a record type, the quoted"
                    + " definition when the material gives one, else null", types = {"string", "null"},
                    example = "Pontos de Vida (PV) medem a saúde do personagem") String definition,
            @ArraySchema(arraySchema = @Schema(description = "One to three examples quoted from the material, each with"
                    + " its page"), minItems = 1, maxItems = 3, schema = @Schema(implementation = ExampleView.class))
            List<ExampleView> examples,
            @Schema(description = "pending until the GM decides", allowableValues = {"pending", "approved", "merged",
                "rejected"}, example = "pending") String state,
            @Schema(description = "The proposal's version: a decision names it", format = "int64", example = "0")
            long version) {

        static ProposalView of(Proposals.Proposal proposal) {
            return new ProposalView(proposal.id().toString(), proposal.systemId().toString(),
                    proposal.materialId() == null ? null : proposal.materialId().toString(), proposal.materialName(),
                    proposal.kind(), proposal.name(), proposal.definition(), proposal.examples().stream()
                    .map(example -> new ExampleView(example.page(), example.quote())).toList(), proposal.state(),
                    proposal.version());
        }

        @Override
        public String toString() {
            return "ProposalView[id=" + id + ", kind=" + kind + ", state=" + state + ", version=" + version + "]";
        }
    }

    @Schema(name = "ProposalPage", requiredProperties = {"items", "total"})
    record ProposalPageView(
            @ArraySchema(schema = @Schema(implementation = ProposalView.class)) List<ProposalView> items,
            @Schema(description = "How many pending proposals there are in all, whatever the page", format = "int64",
                    example = "3") long total) {
    }

    @Schema(name = "ProposalDecision", requiredProperties = {"proposal", "resultId"})
    record DecisionView(
            @Schema(description = "The proposal as stored, decided") ProposalView proposal,
            @Schema(description = "The record type or term the approval made, or the one the merge went into; null for a"
                    + " rejection", format = "uuid", types = {"string", "null"},
                    example = "0190a0b0-0000-7000-8000-000000000031") String resultId) {

        static DecisionView of(Proposals.Decision decision) {
            return new DecisionView(ProposalView.of(decision.proposal()),
                    decision.resultId() == null ? null : decision.resultId().toString());
        }
    }

    @Schema(name = "ProposalApproval", requiredProperties = {"version"})
    record ApprovalBody(
            @Schema(description = "The version the decision was made on, as the proposal was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The name to approve it under, 1 to 120 code points after trimming; the proposed name"
                    + " when absent or null", types = {"string", "null"}, minLength = 1, maxLength = 120,
                    example = "Feat") String name) {

        @Override
        public String toString() {
            return "ApprovalBody[version=" + version + "]";
        }
    }

    @Schema(name = "ProposalMerge", requiredProperties = {"version", "targetId"})
    record MergeBody(
            @Schema(description = "The version the decision was made on, as the proposal was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "A record type of the proposal's system for a record type proposal, or a term of it"
                    + " for a term proposal", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000031")
            String targetId) {
    }

    @Schema(name = "ProposalRejection", requiredProperties = {"version"})
    record RejectionBody(
            @Schema(description = "The version the decision was made on, as the proposal was read", format = "int64",
                    minimum = "0", example = "0") Long version) {
    }

    @Schema(name = "Term", requiredProperties = {"id", "name", "definition", "aliases", "origin", "version"})
    record TermView(
            @Schema(description = "The term's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000031")
            String id,
            @Schema(description = "The term's name, as approved", example = "Pontos de Vida") String name,
            @Schema(description = "Its definition, quoted from its material",
                    example = "Pontos de Vida (PV) medem a saúde do personagem") String definition,
            @ArraySchema(arraySchema = @Schema(description = "Its other names: those of the term proposals merged into"
                    + " it, oldest first"), schema = @Schema(type = "string", example = "PV")) List<String> aliases,
            @Schema(description = "The approving proposal and its material") OntologyController.OriginView origin,
            @Schema(description = "The term's version", format = "int64", example = "0") long version) {

        static TermView of(Terms.Term term) {
            return new TermView(term.id().toString(), term.name(), term.definition(), term.aliases(),
                    OntologyController.OriginView.of(term.origin()), term.version());
        }

        @Override
        public String toString() {
            return "TermView[id=" + id + ", aliases=" + aliases.size() + ", version=" + version + "]";
        }
    }

    @Schema(name = "TermPage", requiredProperties = {"items", "total"})
    record TermPageView(
            @ArraySchema(schema = @Schema(implementation = TermView.class)) List<TermView> items,
            @Schema(description = "How many terms the system has in all, whatever the page", format = "int64",
                    example = "12") long total) {
    }

    private static final String PAGE_SUMMARY = " A page starts at an offset and names the total of the list."
            + " request.invalid (400) for a limit outside 1 to 50 or an offset outside 0 to 100000;";

    @GetMapping(path = SYSTEM_PROPOSALS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSystemProposals", summary = "The pending ontology proposals of one of the account's game"
            + " systems (contract 1.38.0): the record types and terms its materials proposed, oldest first, each with"
            + " its examples. A proposal joins nothing until the GM approves it. request.not_found (404) for any other"
            + " system id, a system being deleted included;" + PAGE_SUMMARY + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many to skip, 0 to 100000, for a"
                        + " numbered page", schema = @Schema(type = "integer", format = "int32", minimum = "0",
                                maximum = "100000", defaultValue = "0"))})
    ResponseEntity<ProposalPageView> systemProposals(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = OntologyController.limit(limit);
        int from = OntologyController.offset(offset);
        return page(OntologyController.parse(id).flatMap(systemId -> proposals.ofSystem(systemId, from, size)));
    }

    @GetMapping(path = MATERIAL_PROPOSALS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMaterialProposals", summary = "The pending ontology proposals of one of the account's"
            + " materials (contract 1.38.0), in every system it counts under (each proposal names its system), oldest"
            + " first. request.not_found (404) for any other material id;" + PAGE_SUMMARY + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many to skip, 0 to 100000, for a"
                        + " numbered page", schema = @Schema(type = "integer", format = "int32", minimum = "0",
                                maximum = "100000", defaultValue = "0"))})
    ResponseEntity<ProposalPageView> materialProposals(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = OntologyController.limit(limit);
        int from = OntologyController.offset(offset);
        return page(OntologyController.parse(id).flatMap(materialId -> proposals.ofMaterial(materialId, from, size)));
    }

    @PostMapping(path = APPROVAL, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveProposal", summary = "Approves one of the account's pending ontology proposals on"
            + " the version it names (contract 1.38.0), as proposed or under another name: a record type proposal"
            + " becomes a record type of its system (its key the slug of the approved name, its origin the proposal),"
            + " a term proposal a term with its quoted definition. ontology.name_taken (409) when the system already has"
            + " the name (a record type, a term, an alias, or a core type's name in pt-BR or en, trimmed, whatever the"
            + " case); ontology.name_invalid (400) for a name outside its limits (params min and max);"
            + DECISION_SUMMARY + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<DecisionView> approve(
            @Parameter(description = "The proposal's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody ApprovalBody body) {
        UUID proposalId = OntologyController.parse(id).orElseThrow(OntologyController::notFound);
        if (body == null) {
            throw OntologyController.invalid();
        }
        return decided(proposals.approve(proposalId, version(body.version()), body.name()));
    }

    @PostMapping(path = MERGE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "mergeProposal", summary = "Merges one of the account's pending ontology proposals into an"
            + " item of its system on the version it names (contract 1.38.0): a term proposal into a term, whose aliases"
            + " gain the proposal's name; a record type proposal into a record type, which only closes the proposal."
            + " ontology.merge_target_invalid (400) for a target that is not of the proposal's kind and system (another"
            + " account's, unknown or malformed included); ontology.name_taken (409) when the system already has the"
            + " term proposal's name;" + DECISION_SUMMARY + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<DecisionView> merge(
            @Parameter(description = "The proposal's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody MergeBody body) {
        UUID proposalId = OntologyController.parse(id).orElseThrow(OntologyController::notFound);
        if (body == null || body.targetId() == null) {
            throw OntologyController.invalid();
        }
        long version = version(body.version());
        // A malformed target is no different from an unknown one: the merge refuses it as not of the proposal's system.
        UUID target = OntologyController.parse(body.targetId()).orElse(NO_TARGET);
        return decided(proposals.merge(proposalId, version, target));
    }

    @PostMapping(path = REJECTION, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectProposal", summary = "Rejects one of the account's pending ontology proposals on the"
            + " version it names (contract 1.38.0): it is never raised again for the same material, a reprocess"
            + " included;" + DECISION_SUMMARY + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<DecisionView> reject(
            @Parameter(description = "The proposal's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody RejectionBody body) {
        UUID proposalId = OntologyController.parse(id).orElseThrow(OntologyController::notFound);
        if (body == null) {
            throw OntologyController.invalid();
        }
        return decided(proposals.reject(proposalId, version(body.version())));
    }

    @GetMapping(path = SYSTEM_TERMS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listTerms", summary = "The approved terms of one of the account's game systems (contract"
            + " 1.38.0), oldest first, each with its quoted definition, its aliases and its origin. request.not_found"
            + " (404) for any other system id, a system being deleted included;" + PAGE_SUMMARY + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many to skip, 0 to 100000, for a"
                        + " numbered page", schema = @Schema(type = "integer", format = "int32", minimum = "0",
                                maximum = "100000", defaultValue = "0"))})
    ResponseEntity<TermPageView> terms(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = OntologyController.limit(limit);
        int from = OntologyController.offset(offset);
        Terms.TermPage page = OntologyController.parse(id).flatMap(systemId -> terms.terms(systemId, from, size))
                .orElseThrow(OntologyController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TermPageView(
                page.items().stream().map(TermView::of).toList(), page.total()));
    }

    private static long version(Long version) {
        if (version == null || version < 0) {
            throw OntologyController.invalid();
        }
        return version;
    }

    private static ResponseEntity<ProposalPageView> page(Optional<Proposals.ProposalPage> found) {
        Proposals.ProposalPage page = found.orElseThrow(OntologyController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ProposalPageView(
                page.items().stream().map(ProposalView::of).toList(), page.total()));
    }

    private static ResponseEntity<DecisionView> decided(Optional<Proposals.Decision> decision) {
        return OntologyController.found(decision.map(DecisionView::of));
    }
}
