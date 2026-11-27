/**
 * What other modules may use of {@code scope}: {@link io.github.gmshield.scope.service.Scopes} resolves a campaign's
 * scope, builds the input of a job that carries a question, and reads passages within it;
 * {@link io.github.gmshield.scope.service.CampaignScopes} (Story 3.5) reads and changes a campaign's stored settings
 * and serves a page on the ground "the current scope". {@link io.github.gmshield.scope.service.CampaignArtifacts}
 * (Story 5.1 part B) and {@link io.github.gmshield.scope.service.CampaignOverrides} (Story 5.5 part A) are ports other
 * modules implement.
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.scope.service;
