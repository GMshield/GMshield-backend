/**
 * Materials (Story 1.13 onward): what a GM adds to the library, starting with the resumable upload of its file. A
 * {@code material} and its {@code material_version}s are library tables (owner column, Hibernate {@code @TenantId},
 * JPA and JPQL only, names encrypted through {@code vault}); an {@code upload} is a working row of the tus protocol
 * that holds no content, read and written through JDBC. The file itself goes to the {@code vault} file store,
 * encrypted piece by piece as it arrives. Another owner's item is "not found", exactly like an unknown one.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Materials",
        allowedDependencies = {"shared :: service", "vault :: service", "library :: service"})
package io.github.gmshield.materials;
