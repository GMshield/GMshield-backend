/**
 * Knowledge (Story 4.1 onward): each system's ontology, which its owner owns. Story 4.1 stores the record types of a
 * system, {@code record_type}: every system has the eight core types (rule, spell, item, creature, character sheet,
 * encounter, dungeon, note), seeded in the transaction that creates it ({@code library.service.SystemCreated}) and, for
 * the systems from before V34, once at start. A core type is named by its fixed plaintext key and can be neither renamed
 * nor removed. Every table is a library table (owner column, Hibernate {@code @TenantId}, JPA and JPQL only, names
 * encrypted through {@code vault}); another owner's item is "not found", exactly like an unknown one. Its part of a
 * system's deletion is {@code service.SystemRecordTypesDeletion} (a {@code library.service.SystemDeletionHandler}), so
 * {@code library} depends on nothing here. From Story 4.2 it stores the records a version's ingestion extracts
 * ({@code record}, {@code record_passage}) for the {@code materials} module, through the port
 * {@code materials.service.ExtractedRecords} that {@code service.ExtractedRecordStore} implements, so {@code materials}
 * depends on nothing here either; and each record type's count is the records of the system's {@code ready} versions
 * of that core type. From Story 4.3 {@code service.Records} reads a system's records (a type's list, one record, its
 * version's pages) through {@code materials.service.RecordSources}, never {@code ScopeReads}. Terms and proposals (4.4)
 * come later.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Knowledge",
        allowedDependencies = {"shared :: service", "vault :: service", "library :: service", "materials :: service"})
package io.github.gmshield.knowledge;
