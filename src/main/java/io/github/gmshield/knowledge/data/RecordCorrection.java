package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * The values a correction writes over a record (Story 4.5): its type key, its name and fields as vault ciphertexts bound
 * to the row they are written to, the name's keyed fingerprint, and the time of the correction. Equality compares the
 * bytes; {@code toString} shows the type and lengths only, never a stored value.
 */
public record RecordCorrection(String type, byte[] name, byte[] nameFingerprint, byte[] fields, Instant correctedAt) {

    public RecordCorrection {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(correctedAt, "correctedAt");
        name = name.clone();
        nameFingerprint = nameFingerprint.clone();
        fields = fields.clone();
    }

    @Override
    public byte[] name() {
        return name.clone();
    }

    @Override
    public byte[] nameFingerprint() {
        return nameFingerprint.clone();
    }

    @Override
    public byte[] fields() {
        return fields.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecordCorrection that && type.equals(that.type) && Arrays.equals(name, that.name)
                && Arrays.equals(nameFingerprint, that.nameFingerprint) && Arrays.equals(fields, that.fields)
                && correctedAt.equals(that.correctedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), Arrays.hashCode(fields),
                correctedAt);
    }

    @Override
    public String toString() {
        return "RecordCorrection[type=" + type + ", name=" + name.length + " bytes, fields=" + fields.length
                + " bytes]";
    }
}
