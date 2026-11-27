/**
 * What other modules may use of {@code conversations}: from Story 5.4 part A, the {@code artifacts} module reads an
 * offered draft and marks it saved ({@code Conversations#saveable}, {@code Conversations#markSaved}, with
 * {@code TurnReference}); from Story 5.4 part B the {@code artifacts} module implements the
 * {@code ConversationArtifacts} port (a conversation's saved artifacts, for its deletion) and reads which conversations
 * are ready ({@code Conversations#ready}, an artifact's link); nothing else. Its job handler ({@code AnswerJobs}) is
 * reached by the {@code jobs} module through its own {@code JobHandler} interface.
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.conversations.service;
