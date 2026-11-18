/**
 * The accounts module's stores: the table {@code account} (Postgres), and the sign-in attempts and
 * sessions in Redis under {@code gmshield:sign-in:} and {@code gmshield:session:}. Redis holds ids, times,
 * flags and vault token hashes only, never a token.
 */
package io.github.gmshield.accounts.data;
