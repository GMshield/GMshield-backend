/**
 * The limits module's store (Story 2.4): {@code model_call_usage}, one row per model call keyed by the worker's call id,
 * through {@code JdbcClient}, written once ({@code ON CONFLICT DO NOTHING}) in the caller's transaction. Ids, words and
 * token counts only.
 */
package io.github.gmshield.limits.data;
