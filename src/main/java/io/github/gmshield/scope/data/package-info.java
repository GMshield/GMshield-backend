/**
 * Story 3.5: the scope settings each campaign stores, {@code scope_setting} (its three tier switches, model knowledge
 * and a version, Hibernate {@code @Version}) and {@code scope_material_off} (the materials switched off), through JPA
 * entities and JPQL only: Hibernate fills and filters {@code owner_id} ({@code @TenantId}) from the owner of the work,
 * so no query here names an owner and none can reach another owner's rows. A campaign with no row has the starting
 * settings at version 0; the first change inserts the row.
 */
package io.github.gmshield.scope.data;
