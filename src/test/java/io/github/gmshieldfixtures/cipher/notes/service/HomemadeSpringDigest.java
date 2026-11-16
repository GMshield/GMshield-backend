package io.github.gmshieldfixtures.cipher.notes.service;

import org.springframework.util.DigestUtils;

/**
 * Outside the vault: Spring's digest helper, which the cipher rule must refuse. It lives in the test
 * source set because the boundary fixtures do not have Spring on their classpath.
 */
public class HomemadeSpringDigest {

    public String hash(byte[] value) {
        return DigestUtils.md5DigestAsHex(value);
    }
}
