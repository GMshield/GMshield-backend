/**
 * The library (Story 1.11 onward): game systems and campaigns now, materials and their passages later. Every
 * table carries its owner, set and filtered by Hibernate {@code @TenantId} from the {@code OwnerContext}, and
 * is read and written through JPA entities and JPQL only, never native SQL. Names and content are encrypted
 * through {@code vault}. Another owner's item is "not found", exactly like an unknown one. From Story 3.1 part B a
 * system's deletion is recorded in {@code deletion}, which is not a library table (ids and times only, JDBC); each
 * module that owns part of what goes removes it through {@code service.SystemDeletionHandler}, so this module still
 * depends on {@code shared} and {@code vault} only.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Library",
        allowedDependencies = {"shared :: service", "vault :: service"})
package io.github.gmshield.library;
