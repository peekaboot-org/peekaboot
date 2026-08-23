package org.peekaboot.backend.masking;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The masking rules themselves - pure data, no matching logic. {@link MaskingEngine} is
 * what applies these to a key and/or a value.
 *
 * <p>{@link #KEY_NAME_RULES} lists compound, separator-delimited key names ("api-key",
 * "client-secret", ...). {@link MaskingEngine} normalises both these rule names and the
 * key under test into token lists before comparing, so "api-key", "apiKey" and "API_KEY"
 * all match the same rule regardless of whether the key uses dots, hyphens, underscores
 * or camelCase. Bare "key" is deliberately absent from this list: it would mask
 * spring.jpa.key-generator, server.ssl.key-store (a filesystem path, not a secret) and
 * key-alias.
 *
 * <p>{@link #LEGACY_KEY_PATTERNS} carries a handful of Spring Boot 2.x's removed
 * {@code Sanitizer} defaults that don't fit that compound-name shape - they are matched
 * as plain case-insensitive regexes against the whole key instead.
 *
 * <p>{@link #VALUE_PATTERNS} carries high-precision, provider-prefixed patterns that
 * catch a credential sitting inside a value under an innocuous key (a JDBC URL's
 * password, a bearer token in a header, a key pasted into SQL text). Deliberately does
 * not include entropy-based detection - see the design spec's "Explicitly rejected"
 * section for why.
 */
final class MaskingRules {

    /** Spring's own literal for a masked value ({@code org.springframework.boot.actuate.endpoint.Sanitizer}). */
    static final String MASK = "******";

    static final List<String> KEY_NAME_RULES = List.of(
        "password", "passwd", "pwd", "passphrase",
        "secret", "client-secret",
        "token", "access-token", "refresh-token", "id-token", "auth-token", "bearer",
        "credential", "credentials",
        "api-key", "apikey", "private-key", "secret-key", "signing-key", "encryption-key",
        "authorization", "auth",
        "cookie", "set-cookie", "session-id",
        "salt", "signature", "certificate"
    );

    static final List<Pattern> LEGACY_KEY_PATTERNS = List.of(
        Pattern.compile("vcap_services", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^vcap\\.services.*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("sun.java.command", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^spring[._]application[._]json$", Pattern.CASE_INSENSITIVE)
    );

    static final List<ValuePattern> VALUE_PATTERNS = List.of(
        new ValuePattern("JWT",
            Pattern.compile("\\bey[A-Za-z0-9]{17,}\\.ey[A-Za-z0-9/\\\\_-]{17,}\\.(?:[A-Za-z0-9/\\\\_-]{10,}={0,2})?")),
        new ValuePattern("PEM private key",
            Pattern.compile("(?i)-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----")),
        new ValuePattern("AWS access key",
            Pattern.compile("\\b(?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16}\\b")),
        new ValuePattern("GitHub token",
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[0-9A-Za-z]{36}\\b")),
        new ValuePattern("GitHub fine-grained token",
            Pattern.compile("github_pat_[0-9A-Za-z_]{22,}")),
        new ValuePattern("GCP API key",
            Pattern.compile("\\bAIza[\\w-]{35}\\b")),
        new ValuePattern("Slack token",
            Pattern.compile("\\bxox[baprs]-[0-9A-Za-z-]{10,}\\b")),
        new ValuePattern("Stripe key",
            Pattern.compile("\\b[sr]k_live_[0-9A-Za-z]{20,}\\b")),
        new ValuePattern("OpenAI key",
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b")),
        new ValuePattern("Anthropic key",
            Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b")),
        // The upstream regex has no capturing group; group 1 is added here so
        // MaskingEngine can mask the userinfo only, leaving scheme://host:port/path intact.
        new ValuePattern("Credentials in a URL", 1,
            Pattern.compile("[a-z][a-z0-9+.-]*://([^/\\s:@]+:[^/\\s:@]+)@")),
        // Distinct from "Credentials in a URL" above and not subsumed by it: this is the
        // query-parameter shape (scheme://host/db?password=...), the more common of the
        // two for JDBC. Group 2 (the value only) is masked, leaving the parameter name
        // and the rest of the URL intact.
        new ValuePattern("Credentials in a URL query", 2,
            Pattern.compile("(?i)([?&;](?:password|passwd|pwd|secret|token|api[-_]?key|access[-_]?key)=)([^&;\\s]+)"))
    );

    private MaskingRules() {
    }

    /**
     * One value-shape rule. When {@code maskGroup} is 0 the whole match is masked;
     * otherwise only that capturing group is masked, keeping the rest of the match
     * (a URL's scheme and trailing "@", for instance) intact.
     */
    record ValuePattern(String name, int maskGroup, Pattern pattern) {
        ValuePattern(String name, Pattern pattern) {
            this(name, 0, pattern);
        }
    }
}
