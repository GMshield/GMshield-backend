/**
 * Conversations (Story 2.4 onward): a campaign's one conversation, its turns (a GM's question, encrypted, with its
 * language, the scope settings frozen with it and its {@code question.answer} job, or {@code content.draft} for a draft
 * (Story 5.3 part A), that answers it) and the answer events of each turn, stored encrypted in numbered batches as the
 * worker posts them and relayed to the GM as server-sent events. Every table is a library table (owner column,
 * Hibernate {@code @TenantId}, JPA and JPQL only, content encrypted through {@code vault}); what a turn may read comes
 * from {@code scope} only. Another owner's item is "not found", exactly like an unknown one. From Story 3.1 part B,
 * {@code service.SystemConversationsDeletion} is this module's part of a system's deletion
 * ({@code library.service.SystemDeletionHandler}): the conversations of its campaigns, their answer jobs cancelled
 * first. From Story 5.4 part B a conversation's deletion lists and may take the artifacts saved from it through its own
 * {@code service.ConversationArtifacts} port, which the {@code artifacts} module implements.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Conversations",
        allowedDependencies = {"shared :: service", "jobs :: service", "scope :: service", "library :: service",
                "vault :: service"})
package io.github.gmshield.conversations;
