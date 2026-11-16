/**
 * The accounts module's stores: the table {@code account} (Postgres), and the sign-in attempts and
 * sessions in Redis under {@code gmshield:sign-in:} and {@code gmshield:session:}, keyed by vault
 * token hashes only.
 */
package io.github.gmshield.accounts.data;
