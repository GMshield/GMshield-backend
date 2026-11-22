/**
 * Scope (Story 2.2, AD-9): the one resolver of what a campaign may use. It turns a campaign and its
 * {@link io.github.gmshield.scope.service.ScopeSettings} into the allowed sources of each tier (the {@code ready}
 * versions of the campaign's materials, of the materials of its game system and, from Story 3.5, of the owner's other
 * materials), leaving out the materials switched off, names the in-scope passages whose index change is still pending,
 * and serves passage text by id only within that scope, labelled with its tier. It reads the library through
 * {@code materials.service.ScopeReads}, which no other module may use. It also builds the one input of a job that
 * carries a question (Story 2.3), with the owner's token key from {@code vault}.
 *
 * <p>Story 3.5: it stores each campaign's settings in PostgreSQL only ({@code data}: {@code scope_setting} and
 * {@code scope_material_off}, never in the index, AD-10), serves them on the public API ({@code web}), serves a page on
 * the ground "the current scope" ({@code service.CampaignScopes}), and removes them with the campaigns or materials
 * going ({@code service.ScopeDeletion}, a {@code library.service.SystemDeletionHandler}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scope",
        allowedDependencies = {"shared :: service", "library :: service", "materials :: service",
                "vault :: service"})
package io.github.gmshield.scope;
