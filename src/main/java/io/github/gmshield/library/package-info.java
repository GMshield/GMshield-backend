/**
 * The library (Story 1.11 onward): game systems and campaigns now, materials and their passages later. Every
 * table carries its owner, set and filtered by Hibernate {@code @TenantId} from the {@code OwnerContext}, and
 * is read and written through JPA entities and JPQL only, never native SQL. Names and content are encrypted
 * through {@code vault}. Another owner's item is "not found", exactly like an unknown one.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Library",
        allowedDependencies = {"shared :: service", "vault :: service"})
package io.github.gmshield.library;
