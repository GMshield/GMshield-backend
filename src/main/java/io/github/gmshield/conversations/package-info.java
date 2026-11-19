/**
 * Conversations (Story 2.4 onward): a campaign's one conversation, its turns (a GM's question, encrypted, with its
 * language, the scope settings frozen with it and the job of kind {@code question.answer} that answers it) and the
 * answer events of each turn, stored encrypted in numbered batches as the worker posts them and relayed to the GM as
 * server-sent events. Every table is a library table (owner column, Hibernate {@code @TenantId}, JPA and JPQL only,
 * content encrypted through {@code vault}); what a turn may read comes from {@code scope} only. Another owner's item is
 * "not found", exactly like an unknown one.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conversations",
        allowedDependencies = {"shared :: service", "jobs :: service", "scope :: service", "library :: service",
                "vault :: service"})
package io.github.gmshield.conversations;
