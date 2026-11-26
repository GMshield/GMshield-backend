/**
 * What other modules may use of {@code knowledge}: nothing yet but the deletion handler, which {@code library} finds as a
 * bean. Story 4.2's internal API reads the record types through {@link io.github.gmshield.knowledge.service.Ontology}.
 * Story 5.1: the {@code artifacts} module stores and reads an artifact's identity through
 * {@link io.github.gmshield.knowledge.service.ArtifactRecords}.
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.knowledge.service;
