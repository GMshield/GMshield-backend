/**
 * Encryption at rest (AD-19): every owner's data key wrapped by the master key, authenticated
 * encryption bound to entity, field, row and owner, keyed fingerprints and token hashes, and the
 * encrypted file store. The only module that may use a cipher, a MAC, a digest or a signature.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Vault", allowedDependencies = {"shared :: service"})
package io.github.gmshield.vault;
