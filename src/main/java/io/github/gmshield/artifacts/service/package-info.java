/**
 * The artifacts' services: {@link io.github.gmshield.artifacts.service.Artifacts} (Story 5.1), which the module's own
 * routes call and which the named interface exports too; the deletion handler, which {@code library} finds as a bean;
 * and from Story 5.1 part B the index jobs of the artifacts ({@code ArtifactIndexing}, {@code ArtifactIndexRemovals}),
 * which {@code jobs} finds as beans, their backfill and retries, and the {@code scope} module's
 * {@code CampaignArtifacts} port ({@code ArtifactSources}).
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.artifacts.service;
