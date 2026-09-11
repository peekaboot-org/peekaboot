package org.peekaboot.backend.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * A password as it is allowed to reach disk: PBKDF2-HMAC-SHA256 over a random salt.
 *
 * <p>The iteration count is not what protects a generated password - 26 characters from
 * {@code DashboardCredentialsResolver}'s alphabet carry about 130 bits, which is not guessable
 * at any count. It is set where one verification costs a fraction of a second, because the
 * dashboard authenticates on every XHR and {@code CredentialCache} only absorbs the repeats.
 */
public final class PasswordHash {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final String SEPARATOR = "$";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("\\$");
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int FIELDS = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;
    private final byte[] salt;
    private final byte[] hash;

    private PasswordHash(int iterations, byte[] salt, byte[] hash) {
        this.iterations = iterations;
        this.salt = salt;
        this.hash = hash;
    }

    public static PasswordHash of(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new PasswordHash(ITERATIONS, salt, derive(password, salt, ITERATIONS));
    }

    /** Empty for anything this class did not write, so a damaged file is a regeneration rather than a crash. */
    public static Optional<PasswordHash> parse(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        String[] fields = SEPARATOR_PATTERN.split(encoded, -1);
        if (fields.length != FIELDS || !PREFIX.equals(fields[0])) {
            return Optional.empty();
        }
        try {
            int iterations = Integer.parseInt(fields[1]);
            if (iterations <= 0) {
                return Optional.empty();
            }
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] salt = decoder.decode(fields[2]);
            byte[] hash = decoder.decode(fields[3]);
            if (salt.length == 0 || hash.length == 0) {
                // PBEKeySpec rejects a zero-length salt, and matches() must never throw
                return Optional.empty();
            }
            return Optional.of(new PasswordHash(iterations, salt, hash));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String format() {
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX
                + SEPARATOR
                + iterations
                + SEPARATOR
                + encoder.encodeToString(salt)
                + SEPARATOR
                + encoder.encodeToString(hash);
    }

    public boolean matches(String password) {
        return password != null && MessageDigest.isEqual(hash, derive(password, salt, iterations));
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // PBKDF2WithHmacSHA256 is mandatory in every JDK this library runs on
            throw new IllegalStateException("PBKDF2 is unavailable on this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Never the salt or the hash: this object is logged by whatever logs the credentials. */
    @Override
    public String toString() {
        return PREFIX + SEPARATOR + iterations + SEPARATOR + "<redacted>";
    }
}
