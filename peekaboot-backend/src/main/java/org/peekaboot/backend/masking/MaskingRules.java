package org.peekaboot.backend.masking;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The masking rules themselves - pure data, no matching logic. {@link MaskingEngine} is
 * what applies these to a key and/or a value.
 */
final class MaskingRules {

    /** Spring's own literal for a masked value ({@code org.springframework.boot.actuate.endpoint.Sanitizer}). */
    static final String MASK = "******";

    /**
     * Compound, separator-delimited key names. {@link MaskingEngine} normalises both these
     * and the key under test into token lists before comparing, so "api-key", "apiKey" and
     * "API_KEY" all match the same rule whatever separator the key uses. Bare "key" is
     * deliberately absent: it would mask spring.jpa.key-generator, server.ssl.key-store (a
     * filesystem path, not a secret) and key-alias. Bare "certificate" is absent for the
     * same reason - server.ssl.certificate is a path too; actual key material is caught by
     * the PEM value pattern, so only the two compound names that name a secret outright
     * are listed.
     */
    static final List<String> KEY_NAME_RULES = List.of(
            "password",
            "passwd",
            "pwd",
            "passphrase",
            "secret",
            "client-secret",
            "token",
            "access-token",
            "refresh-token",
            "id-token",
            "auth-token",
            "bearer",
            "credential",
            "credentials",
            "api-key",
            "apikey",
            "access-key",
            "private-key",
            "secret-key",
            "signing-key",
            "encryption-key",
            "authorization",
            "auth",
            "session-id",
            "salt",
            "signature",
            // Azure SAS's abbreviated signature parameter (?sig=). Matched as an exact
            // token like every rule here, so "design"/"signal" stay untouched.
            "sig",
            "certificate-password",
            "certificate-private-key");

    /**
     * Final key-name tokens that make the key an address rather than a secret:
     * {@code spring.security.oauth2.client.provider.<x>.token-uri} and
     * {@code .authorization-uri} are public endpoints, and among the first properties read
     * when an OAuth2 login misbehaves. Only the last token counts, so "app.token-uri.password"
     * still names a password, and only {@link MaskingEngine}'s key vocabulary is waived - a
     * credential carried inside such a URL is still caught by {@link #VALUE_PATTERNS}.
     */
    static final List<String> ENDPOINT_KEY_SUFFIXES = List.of("uri", "url");

    /**
     * Key names sensitive only as the entire key, not as one token inside a longer compound
     * name: "cookie" and "set-cookie" name an HTTP header outright, but the token also
     * appears inside ordinary session-cookie configuration
     * (server.servlet.session.cookie.same-site and siblings), which is not a secret and is
     * exactly what someone opens the Environment tab to check. The two attribute names are
     * the OpenTelemetry header-capture spellings of the same headers on a span.
     */
    static final List<String> WHOLE_KEY_NAME_RULES =
            List.of("cookie", "set-cookie", "http.request.header.cookie", "http.response.header.set-cookie");

    /**
     * Whole keys that would otherwise match a {@link #KEY_NAME_RULES} entry but are
     * well-known non-secrets under that exact, case-sensitive spelling: "PWD", the POSIX
     * shell's current-working-directory variable, is the "pwd" password abbreviation in
     * upper case, and every shell sets it, so it hits every developer on the most-viewed
     * property source (systemEnvironment). The exception is that one spelling and nothing
     * wider: a lower-case "pwd" is how a SQL Server JDBC URL (";pwd=") or a login form
     * ("?pwd=") names a password and still masks, as does a compound like "db.pwd".
     *
     * <p>Three environment variables mask through the ordinary rules and are deliberately
     * not exempted here: XDG_SESSION_ID, SSH_AUTH_SOCK and CREDENTIALS_DIRECTORY, caught
     * by "session-id", "auth" and "credentials". None is a secret; all three are
     * sensitive-adjacent, and the rules that catch them earn their keep elsewhere.
     */
    static final List<String> KEY_NAME_EXCEPTIONS = List.of("PWD");

    /**
     * Spring Boot's {@code Sanitizer} key patterns that do not fit the compound-name shape,
     * matched as plain case-insensitive regexes against the whole key.
     */
    static final List<Pattern> SPRING_SANITIZER_KEY_PATTERNS = List.of(
            Pattern.compile("vcap_services", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^vcap\\.services.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("sun.java.command", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^spring[._]application[._]json$", Pattern.CASE_INSENSITIVE));

    /**
     * High-precision, provider-prefixed patterns that catch a credential sitting inside a
     * value under an innocuous key (a JDBC URL's password, a bearer token in a header, a
     * key pasted into SQL text). Deliberately no entropy-based detection: a git SHA, a UUID
     * or a base64-encoded asset would trip it, and those must stay readable. Two of the
     * patterns are key/value pairs embedded in a value - a URL's query parameters, a
     * command line's {@code -Dname=value} options - and carry no vocabulary of their own:
     * {@link MaskingEngine} judges the captured key with the key-name rules above, so a
     * name that masks as a property masks as a query parameter too.
     */
    static final List<ValuePattern> VALUE_PATTERNS = List.of(
            new ValuePattern(
                    "JWT",
                    Pattern.compile(
                            "\\bey[A-Za-z0-9]{17,}\\.ey[A-Za-z0-9/\\\\_-]{17,}\\.(?:[A-Za-z0-9/\\\\_-]{10,}={0,2})?")),
            new ValuePattern(
                    "PEM private key", Pattern.compile("(?i)-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----")),
            new ValuePattern(
                    "AWS access key", Pattern.compile("\\b(?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16}\\b")),
            new ValuePattern("GitHub token", Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[0-9A-Za-z]{36}\\b")),
            new ValuePattern("GitHub fine-grained token", Pattern.compile("github_pat_[0-9A-Za-z_]{22,}")),
            new ValuePattern("GCP API key", Pattern.compile("\\bAIza[\\w-]{35}\\b")),
            new ValuePattern("Slack token", Pattern.compile("\\bxox[baprs]-[0-9A-Za-z-]{10,}\\b")),
            new ValuePattern("Stripe key", Pattern.compile("\\b[sr]k_live_[0-9A-Za-z]{20,}\\b")),
            new ValuePattern("OpenAI project key", Pattern.compile("\\bsk-proj-[A-Za-z0-9_-]{20,}\\b")),
            new ValuePattern("Anthropic key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}\\b")),
            // Legacy OpenAI format: "sk-" plus an unbroken alphanumeric run (no "-"/"_"), so
            // an infra name like "sk-cluster-prod-..." breaks at its first hyphen and never
            // reaches the 20-character floor; "sk-proj-"/"sk-ant-" break at the same place
            // and are caught by their own rules above.
            new ValuePattern("Legacy OpenAI key", Pattern.compile("\\bsk-[A-Za-z0-9]{20,}\\b")),
            // Group 1 is the userinfo, so MaskingEngine masks it alone and leaves
            // scheme://host:port/path intact. The user may be empty: redis://:secret@host is
            // the common Redis shape.
            new ValuePattern(
                    "Credentials in a URL", 1, Pattern.compile("[a-z][a-z0-9+.-]*://([^/\\s:@]*:[^/\\s:@]+)@")),
            // Oracle's thin URL (jdbc:oracle:thin:user/password@host) has no "://" ahead of
            // the credentials, so the rule above never sees it. Group 2 is the password.
            new ValuePattern(
                    "Credentials in an Oracle thin URL",
                    2,
                    Pattern.compile("(?i)jdbc:oracle:thin:([^/@\\s]+/)([^@\\s]+)@")),
            // Distinct from "Credentials in a URL" above and not subsumed by it: this is the
            // query-parameter shape (scheme://host/db?password=...), the more common of the
            // two for JDBC; ";" covers SQL Server's property separator. Every pair is a
            // candidate - group 1 is judged by the key-name rules, and only group 2 (the
            // value) is masked, leaving the parameter name and the rest of the URL intact.
            ValuePattern.keyed("Credentials in a URL query", 1, 2, Pattern.compile("[?&;]([^=&;\\s]+)=([^&;\\s]+)")),
            // The value of a -Dname=value / --name=value option, as JAVA_TOOL_OPTIONS,
            // JDK_JAVA_OPTIONS and their kin carry it: the property spring.datasource.password
            // is masked by its own key, but not the option string it was set from unless
            // the option's name is judged the same way. Starts on a word boundary so a
            // "-D" glued onto a preceding token is not mistaken for a flag.
            ValuePattern.keyed(
                    "Credentials in a command-line option",
                    1,
                    2,
                    Pattern.compile("(?<=\\s|^)(?:-D|--)([\\w.-]+)=(\\S+)")));

    private MaskingRules() {}

    /**
     * One value-shape rule. When {@code maskGroup} is 0 the whole match is masked;
     * otherwise only that capturing group is masked, keeping the rest of the match
     * (a URL's scheme and trailing "@", for instance) intact. When {@code keyGroup} is
     * above 0 the match counts only if that group, read as a key, is sensitive by the
     * key-name rules - the pattern then finds candidate pairs and the key rules decide.
     */
    record ValuePattern(String name, int maskGroup, int keyGroup, Pattern pattern) {
        ValuePattern(String name, Pattern pattern) {
            this(name, 0, 0, pattern);
        }

        ValuePattern(String name, int maskGroup, Pattern pattern) {
            this(name, maskGroup, 0, pattern);
        }

        static ValuePattern keyed(String name, int keyGroup, int maskGroup, Pattern pattern) {
            return new ValuePattern(name, maskGroup, keyGroup, pattern);
        }
    }
}
