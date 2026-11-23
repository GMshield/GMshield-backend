/**
 * What other modules may use of {@code library}: {@code accounts} creates a first sign-in's starter library; {@code scope}
 * and {@code conversations} read a campaign with its system; {@code materials} checks a campaign or a system and, from
 * Story 3.1, reads the campaign ids of a system. From Story 3.1 part B, {@code materials} and {@code conversations}
 * implement {@link io.github.gmshield.library.service.SystemDeletionHandler}: their part of a system's deletion.
 */
@org.springframework.modulith.NamedInterface("service")
package io.github.gmshield.library.service;
