/**
 * What other modules may use of {@code conversations}: from Story 5.4 part A, the {@code artifacts} module reads an
 * offered draft and marks it saved ({@code Conversations#saveable}, {@code Conversations#markSaved}, with
 * {@code TurnReference}); nothing else. Its job handler ({@code AnswerJobs}) is reached by the {@code jobs} module
 * through its own {@code JobHandler} interface.
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.conversations.service;
