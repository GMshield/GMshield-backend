/**
 * Scope (Story 2.2, AD-9): the one resolver of what a campaign may use. It turns a campaign and its
 * {@link io.github.gmshield.scope.service.ScopeSettings} into the allowed sources of each tier (the {@code ready}
 * versions of the campaign's materials, and of the materials of its game system), names the in-scope passages whose
 * index change is still pending, and serves passage text by id only within that scope, labelled with its tier. It
 * reads the library through {@code materials.service.ScopeReads}, which no other module may use, and stores nothing.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scope",
        allowedDependencies = {"shared :: service", "library :: service", "materials :: service"})
package io.github.gmshield.scope;
