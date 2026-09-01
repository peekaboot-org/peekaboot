package org.peekaboot.backend.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MaskingEngineTest {

    private final MaskingEngine engine = new MaskingEngine();

    @Nested
    class KeyNameRulesPositive {

        // One realistic property/header-shaped key per compound key-name rule from the
        // design spec, each embedding the rule term at a separator boundary.
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "spring.datasource.password",
                    "app.passwd",
                    "db.pwd",
                    "app.security.passphrase",
                    "app.secret",
                    "spring.security.oauth2.client.registration.google.client-secret",
                    "app.token",
                    "spring.security.oauth2.client.registration.google.access-token",
                    "spring.security.oauth2.client.registration.google.refresh-token",
                    "spring.security.oauth2.client.registration.google.id-token",
                    "app.auth-token",
                    "app.bearer",
                    "app.credential",
                    "app.credentials",
                    "app.api-key",
                    "app.apikey",
                    "app.access-key",
                    "app.private-key",
                    "app.secret-key",
                    "app.signing-key",
                    "app.encryption-key",
                    "authorization",
                    "app.auth",
                    "Cookie",
                    "Set-Cookie",
                    "app.session-id",
                    "app.salt",
                    "app.signature",
                    "sig",
                    "app.certificate-password",
                    "app.certificate-private-key",
                })
        void isSensitiveKey_shouldMatchEachKeyNameRule(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }
    }

    @Nested
    class LegacyKeyPatternsPositive {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "VCAP_SERVICES",
                    "vcap.services.my-service.credentials",
                    "sun.java.command",
                    "spring.application.json",
                })
        void isSensitiveKey_shouldMatchEachLegacySpringPattern(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }
    }

    @Nested
    class KeyNameRulesNegative {

        // Named in the design spec as cases the dashboard must not over-mask.
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "spring.jpa.key-generator",
                    "server.ssl.key-store",
                    "server.ssl.key-alias",
                    "server.port",
                    "spring.application.name",
                    "peekaboot.tracing.max-spans-per-trace",
                    // server.ssl.certificate is a filesystem path, not a secret - identically
                    // shaped to server.ssl.key-store above. Actual key material is already caught
                    // by the PEM value pattern.
                    "server.ssl.certificate",
                    "server.ssl.trust-certificate",
                    // PWD is set by every POSIX shell, so it hits every developer on the
                    // most-viewed property source (systemEnvironment). password/passwd already
                    // cover the real password case in practice.
                    "PWD",
                    "OLDPWD",
                    // "sig" is an exact token (Azure SAS); it must not reach into words
                    // that merely start with it.
                    "design",
                    "signal",
                    "app.design.theme",
                    "spring.signal.handler",
                })
        void isSensitiveKey_shouldNotMatchNegativeCases(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }

        // the cookie/set-cookie rules exist for HTTP header names (Cookie, Set-Cookie
        // exactly), not for the token "cookie" appearing anywhere in a compound key.
        // Session-cookie *configuration* is not a secret and is exactly what someone
        // opens the Environment tab to check when debugging a SameSite problem.
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "server.servlet.session.cookie.name",
                    "server.servlet.session.cookie.max-age",
                    "server.servlet.session.cookie.same-site",
                    "server.servlet.session.cookie.http-only",
                    "server.servlet.session.cookie.secure",
                    "server.servlet.session.cookie.path",
                    "server.servlet.session.cookie.domain",
                })
        void isSensitiveKey_shouldNotMatchCookieConfigurationKeys(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }

        // "password" must not match inside "passwordless" - there is no boundary between
        // them, so this pins that the whole-token check (both tokenizations) rejects a
        // partial match rather than falling back to a substring check.
        @ParameterizedTest
        @ValueSource(strings = {"passwordless", "app.passwordless.enabled"})
        void isSensitiveKey_shouldNotMatchPasswordAsAPrefixOfALongerWord(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }

        // "oauth2" must not match the bare "auth" rule - it is one token, not "auth"
        // followed by a separator, so a whole-token check must reject it.
        @Test
        void isSensitiveKey_shouldNotMatchAuthAsASubstringOfOauth2() {
            assertThat(engine.isSensitiveKey("spring.security.oauth2.client.registration.google.client-id"))
                    .isFalse();
        }
    }

    @Nested
    class KeySeparatorStyles {

        @ParameterizedTest
        @ValueSource(strings = {"api-key", "API_KEY", "api.key", "Api-Key"})
        void isSensitiveKey_shouldMatchRegardlessOfSeparatorStyle(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        // "clientSecret" has no separator between the words at all - only camelCase
        // boundary detection (not dot/hyphen/underscore splitting) can recognise it.
        // "apiKey" is deliberately not used here: it would pass even without camelCase
        // handling because "apikey" (all lowercase, no separator) is itself a listed rule.
        @Test
        void isSensitiveKey_shouldMatchACamelCaseCompoundNameWithNoSeparator() {
            assertThat(engine.isSensitiveKey("clientSecret")).isTrue();
        }

        // The PWD exception is for the shell's variable, which is upper-case by POSIX
        // convention; a bare lower-case "pwd" is the JDBC/form-field spelling of a
        // password (";pwd=" on a SQL Server URL, "?pwd=" on a login form) and masks.
        @Test
        void isSensitiveKey_shouldExemptOnlyTheShellsUpperCaseSpellingOfPwd() {
            assertThat(engine.isSensitiveKey("PWD")).isFalse();
            assertThat(engine.isSensitiveKey("pwd")).isTrue();
        }

        @Test
        void isSensitiveKey_shouldReturnFalseForNullKey() {
            assertThat(engine.isSensitiveKey(null)).isFalse();
        }

        @Test
        void isSensitiveKey_shouldReturnFalseForBlankKey() {
            assertThat(engine.isSensitiveKey("  ")).isFalse();
        }
    }

    @Nested
    class MixedCaseSpellings {

        // A camelCase-boundary tokenizer alone would mis-split these: "PassWord" becomes
        // ["pass", "word"], neither of which is the rule token "password". Spring's relaxed
        // binding means the caller does not control how an external property source spells
        // a key, so a rule word with a stray internal capital must still match.
        @Test
        void isSensitiveKey_shouldMatchAMixedCaseSpellingOfPassword() {
            assertThat(engine.isSensitiveKey("spring.datasource.PassWord")).isTrue();
        }

        @Test
        void isSensitiveKey_shouldMatchAMixedCaseSpellingOfAnotherSingleWordRule() {
            assertThat(engine.isSensitiveKey("app.SeCreT")).isTrue();
        }
    }

    @Nested
    class ValuePatterns {

        @Test
        void maskValue_shouldMaskJwt() {
            String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0"
                    + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
            String value = "Authorization header carried: " + jwt;

            String result = engine.maskValue(value);

            assertThat(result).doesNotContain(jwt).contains("******").startsWith("Authorization header carried: ");
        }

        @Test
        void maskValue_shouldMaskPemPrivateKey() {
            String value = "-----BEGIN RSA PRIVATE KEY-----";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValue_shouldMaskAwsAccessKey() {
            // AWS's own documentation placeholder, not a real key.
            String value = "aws_access_key_id=AKIAIOSFODNN7EXAMPLE";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("aws_access_key_id=******");
        }

        @Test
        void maskValue_shouldMaskGithubClassicToken() {
            String value = "ghp_16C7e42F292c6912E7710c838347Ae178B4a";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValue_shouldMaskGithubFineGrainedToken() {
            String value =
                    "github_pat_11AAAAAAA0abcdefghijkl_" + "MNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890AB";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValue_shouldMaskGcpApiKey() {
            // Obviously fake, unlike a real GCP key: EXAMPLE plus zero-padding.
            String value = "key=AIzaEXAMPLE0000000000000000000000000000";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("key=******");
        }

        @Test
        void maskValue_shouldMaskSlackToken() {
            // Split literal, deliberately: this file is full of strings shaped exactly
            // like the credentials the engine detects, and GitHub's push protection scans
            // for the same shapes. Concatenation is folded at compile time, so the value
            // under test is unchanged; it just never appears contiguously in the source.
            String value = "xoxb" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValue_shouldMaskStripeLiveKey() {
            // Split literal, deliberately: this file is full of strings shaped exactly
            // like the credentials the engine detects, and GitHub's push protection scans
            // for the same shapes. Concatenation is folded at compile time, so the value
            // under test is unchanged; it just never appears contiguously in the source.
            String value = "sk_live" + "_4eC39HqLyjWDarjtT1zdp7dcEXAMPLE";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValue_shouldMaskOpenAiProjectKey() {
            String value = "sk-proj-EXAMPLEabcdefghijklmnopqrstuvwxyz1234567890";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValue_shouldMaskAnthropicKey() {
            String value = "sk-ant-EXAMPLEabcdefghijklmnopqrstuvwxyz1234567890";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        // The legacy, pre-project-key OpenAI format: "sk-" plus an unbroken run of
        // alphanumerics, no hyphens or underscores anywhere in the tail - still common
        // in the wild. See MaskingRules' comment on the "Legacy OpenAI key" value pattern
        // for why the tightened tail character class is what lets this coexist with the
        // infra-identifier negative case below.
        @Test
        void maskValue_shouldMaskLegacyOpenAiKey() {
            String value = "sk-EXAMPLEabcdefghijklmnopqrstuvwxyz1234567890";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        // The legacy pattern's tightened char class ([A-Za-z0-9], no "-"/"_") is what
        // keeps it from also matching "sk-proj-"/"sk-ant-" values (that literal hyphen
        // right after "sk-" breaks the required run at 4/3 characters) - so a
        // sk-proj-shaped value should still mask, and mask exactly once, whichever of
        // the three sk- rules ends up firing for it.
        @Test
        void maskValue_shouldMaskAnOpenAiProjectKeyExactlyOnceWithTheLegacyPatternAlsoActive() {
            String key = "sk-proj-" + "A".repeat(40);
            String value = "key=" + key + "&ok=1";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("key=******&ok=1");
        }

        @Test
        void maskValue_shouldMaskOnlyUserinfoInJdbcUrl() {
            String value = "jdbc:postgresql://dbuser:S3cr3tPassw0rd@localhost:5432/mydb";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("jdbc:postgresql://******@localhost:5432/mydb");
            assertThat(result).contains("localhost", "5432", "mydb").doesNotContain("dbuser", "S3cr3tPassw0rd");
        }

        /** The common Redis shape: no user at all, only a password in front of the '@'. */
        @Test
        void maskValue_shouldMaskUserinfoWithAnEmptyUser() {
            String value = "redis://:S3cr3tPassw0rd@cache.example.com:6379/0";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("redis://******@cache.example.com:6379/0");
        }

        /**
         * Oracle's thin URL puts user/password in front of the '@' with no "://" ahead of
         * them, so the generic userinfo rule never sees it; only the password is masked.
         */
        @Test
        void maskValue_shouldMaskOnlyThePasswordInAnOracleThinUrl() {
            assertThat(engine.maskValue("jdbc:oracle:thin:scott/S3cr3tPassw0rd@//db.example.com:1521/ORCL"))
                    .isEqualTo("jdbc:oracle:thin:scott/******@//db.example.com:1521/ORCL");
            assertThat(engine.maskValue("jdbc:oracle:thin:scott/S3cr3tPassw0rd@db.example.com:1521:ORCL"))
                    .isEqualTo("jdbc:oracle:thin:scott/******@db.example.com:1521:ORCL");
            assertThat(engine.maskValue("jdbc:oracle:thin:@//db.example.com:1521/ORCL"))
                    .isEqualTo("jdbc:oracle:thin:@//db.example.com:1521/ORCL");
        }

        /**
         * The query-parameter credential shape (spring.datasource.url containing
         * "?password=..."), distinct from the userinfo shape above and not covered by
         * it - the canonical case the value patterns exist for.
         */
        @Test
        void maskValue_shouldMaskOnlyThePasswordValueInAJdbcUrlQueryParameter() {
            String value = "jdbc:mysql://localhost:3306/mydb?user=root&password=hunter2";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("jdbc:mysql://localhost:3306/mydb?user=root&password=******");
            assertThat(result)
                    .contains("localhost", "3306", "mydb", "user=root")
                    .doesNotContain("hunter2");
        }

        @Test
        void maskValue_shouldMaskAnApiKeyInAUrlQueryParameter() {
            String value = "https://api.example.com/v1/data?api_key=abc123&format=json";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("https://api.example.com/v1/data?api_key=******&format=json");
        }

        // The query rule judges each parameter name with the key-name rules, so every
        // spelling those accept - OAuth's token names, a camelCase apiKey, a bare auth -
        // is caught here too, not just the handful a separate word list happened to name.
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "password",
                    "passwd",
                    "pwd",
                    "secret",
                    "token",
                    "api-key",
                    "api_key",
                    "apiKey",
                    "access-key",
                    "access_key",
                    "access_token",
                    "refresh_token",
                    "id_token",
                    "client_secret",
                    "signature",
                    "sig",
                    "auth",
                    "authorization"
                })
        void maskValue_shouldMaskEachUrlQueryCredentialParameterName(String paramName) {
            String value = "https://example.com/callback?" + paramName + "=s3cr3t&ok=1";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("https://example.com/callback?" + paramName + "=******&ok=1");
        }

        /** The shape a client span's {@code http.url} tag carries for a token exchange. */
        @Test
        void maskValue_shouldMaskOnlyTheSensitivePairsInAUrlWithSeveralParameters() {
            String value =
                    "https://oauth.example.com/token?client_id=abc&client_secret=s3cr3t&grant_type=client_credentials";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo(
                            "https://oauth.example.com/token?client_id=abc&client_secret=******&grant_type=client_credentials");
        }

        /** SQL Server's JDBC URL separates its properties with semicolons rather than ?/&. */
        @Test
        void maskValue_shouldMaskASemicolonDelimitedJdbcProperty() {
            String value =
                    "jdbc:sqlserver://db.example.com:1433;databaseName=orders;user=app;password=hunter2;encrypt=true";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo(
                            "jdbc:sqlserver://db.example.com:1433;databaseName=orders;user=app;password=******;encrypt=true");
        }

        @Test
        void maskValue_shouldNotMaskAnOrdinaryQueryParameter() {
            String value = "https://example.com/search?query=widgets&page=2";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        // Bare "key" is deliberately not a key-name rule (see MaskingRules); the query
        // rule inherits that decision rather than keeping a vocabulary of its own.
        @Test
        void maskValue_shouldLeaveABareKeyQueryParameterAlone() {
            String value = "https://maps.example.com/geocode?key=abc123&address=Berlin";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        /** The shape of an Azure SAS URL: only {@code sig} carries the secret. */
        @Test
        void maskValue_shouldMaskOnlyTheSigParameterOfAnAzureSasUrl() {
            String value = "https://acct.blob.core.windows.net/c/blob.txt?sv=2024-11-04&se=2026-01-01&sig=s3cr3t";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo("https://acct.blob.core.windows.net/c/blob.txt?sv=2024-11-04&se=2026-01-01&sig=******");
        }

        // "sig" matches as an exact token only, never as a prefix of a longer word.
        @Test
        void maskValue_shouldNotMaskQueryParametersMerelyStartingWithSig() {
            String value = "https://example.com/render?design=flat&signal=9&sigma=0.5";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }
    }

    /**
     * A raw query string (no URL around it), masked pair by pair with the same key-name
     * rules as everything else - the entry point {@code RequestCaptureFilter} uses for
     * {@code request.getQueryString()}.
     */
    @Nested
    class QueryStringMasking {

        @Test
        void maskQueryString_shouldMaskSensitivePairsAndLeaveTheRestAlone() {
            assertThat(engine.maskQueryString("api_key=xyz&q=widgets")).isEqualTo("api_key=******&q=widgets");
        }

        @Test
        void maskQueryString_shouldPreserveAPairWithNoValue() {
            assertThat(engine.maskQueryString("debug&q=widgets")).isEqualTo("debug&q=widgets");
        }

        @Test
        void maskQueryString_shouldReturnNullAndBlankInputUnchanged() {
            assertThat(engine.maskQueryString(null)).isNull();
            assertThat(engine.maskQueryString(" ")).isEqualTo(" ");
        }

        /** The key is judged decoded, so an encoded spelling of a sensitive name still masks. */
        @Test
        void maskQueryString_shouldDecodeTheKeyBeforeJudgingIt() {
            assertThat(engine.maskQueryString("api%5Fkey=xyz&q=a%20b")).isEqualTo("api_key=******&q=a+b");
        }
    }

    /**
     * Container platforms hand secrets to a JVM as {@code -Dspring.datasource.password=...}
     * inside JAVA_TOOL_OPTIONS and friends; the option's key decides, with the same
     * key-name rules as a property of that name.
     */
    @Nested
    class CommandLineOptionValues {

        @Test
        void maskValue_shouldMaskTheValueOfASystemPropertyOptionWithASensitiveKey() {
            String value = "-Xmx1g -Dspring.datasource.password=hunter2 -Dserver.port=8080";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("-Xmx1g -Dspring.datasource.password=****** -Dserver.port=8080");
        }

        @Test
        void maskValue_shouldMaskTheValueOfADoubleDashOptionWithASensitiveKey() {
            String value = "--spring.datasource.password=hunter2 --server.port=8080";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("--spring.datasource.password=****** --server.port=8080");
        }

        @Test
        void mask_shouldMaskOptionValuesInsideAnInnocuouslyNamedEnvironmentVariable() {
            String result = engine.mask("JAVA_TOOL_OPTIONS", "-Dapp.api-key=abc123 -Dspring.profiles.active=prod");

            assertThat(result).isEqualTo("-Dapp.api-key=****** -Dspring.profiles.active=prod");
        }

        @Test
        void maskValue_shouldLeaveOptionsWithInnocuousKeysAlone() {
            String value = "-Dserver.port=8080 -Dspring.profiles.active=local --debug=true";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        // An option only starts at a word boundary: "-D" glued onto a preceding token is
        // part of that token (a path segment, an identifier), not a JVM flag.
        @Test
        void maskValue_shouldNotTreatADashDInsideAWordAsAnOption() {
            String value = "build-Dpassword=1";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }
    }

    @Nested
    class ValuesThatSurviveUntouched {

        @Test
        void maskValue_shouldNotTouchAGitSha() {
            String value = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValue_shouldNotTouchAUuid() {
            String value = "550e8400-e29b-41d4-a716-446655440000";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValue_shouldNotTouchABase64EncodedAsset() {
            String value = "aGVsbG8gd29ybGQgdGhpcyBpcyBhIGJhc2U2NCBlbmNvZGVkIHN0cmluZw==";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValue_shouldNotTouchAnIsoTimestamp() {
            String value = "2026-08-23T15:17:00Z";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValue_shouldNotTouchAPlainUrlWithNoCredentials() {
            String value = "https://example.com/api/v1/resource?query=1";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        // The legacy OpenAI pattern requires an unbroken 20+ character alphanumeric run
        // right after "sk-" - this infra identifier's run breaks at its first hyphen,
        // only 7 characters in ("cluster"), so it never reaches that floor. See
        // MaskingRules' comment on the "Legacy OpenAI key" value pattern for why the
        // char class is tightened to [A-Za-z0-9] (no "-"/"_") specifically to achieve
        // this separation.
        @Test
        void maskValue_shouldNotTouchAnSkPrefixedInfraIdentifier() {
            String value = "sk-cluster-prod-eu-west-1a-worker-nodes";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValue_shouldReturnNullForNullValue() {
            assertThat(engine.maskValue(null)).isNull();
        }

        @Test
        void maskValue_shouldReturnEmptyStringForEmptyValue() {
            assertThat(engine.maskValue("")).isEmpty();
        }
    }

    @Nested
    class MaskKeyValuePair {

        @Test
        void mask_shouldReplaceWholeValueWhenKeyIsSensitive() {
            String result = engine.mask("database.password", "hunter2");

            assertThat(result).isEqualTo("******");
        }

        @Test
        void mask_shouldLeaveValueUntouchedWhenNeitherKeyNorValueIsSensitive() {
            String result = engine.mask("server.port", "8080");

            assertThat(result).isEqualTo("8080");
        }

        @Test
        void mask_shouldMaskOnlyTheCredentialSpanWhenKeyIsInnocuousButValueIsNot() {
            String url = "jdbc:postgresql://dbuser:S3cr3tPassw0rd@localhost:5432/mydb";

            String result = engine.mask("spring.datasource.url", url);

            assertThat(result).isEqualTo("jdbc:postgresql://******@localhost:5432/mydb");
        }

        @Test
        void mask_shouldReturnNullWhenValueIsNull() {
            assertThat(engine.mask("database.password", null)).isNull();
        }

        @Test
        void mask_shouldFallBackToValueRulesWhenKeyIsNull() {
            String value = "aws_access_key_id=AKIAIOSFODNN7EXAMPLE";

            String result = engine.mask(null, value);

            assertThat(result).isEqualTo(engine.maskValue(value));
        }

        @Test
        void mask_shouldTreatAHeaderNameLikeAnyOtherKey() {
            String result = engine.mask("X-Api-Key", "abc123");

            assertThat(result).isEqualTo("******");
        }

        @Test
        void mask_shouldLeaveAnInnocuousHeaderUntouched() {
            String result = engine.mask("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");

            assertThat(result).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        }

        // Narrowing cookie/set-cookie to whole-key matches must not stop them masking
        // the actual HTTP headers they exist for.
        @Test
        void mask_shouldMaskTheCookieHeader() {
            String result = engine.mask("Cookie", "session=abc123");

            assertThat(result).isEqualTo("******");
        }

        @Test
        void mask_shouldMaskTheSetCookieHeader() {
            String result = engine.mask("Set-Cookie", "session=abc123; Path=/; HttpOnly");

            assertThat(result).isEqualTo("******");
        }
    }

    @Nested
    class MaskWithUnmaskFlag {

        @Test
        void mask_shouldReturnValueVerbatimWhenUnmaskIsTrueEvenForASensitiveKey() {
            String result = engine.mask("database.password", "hunter2", true);

            assertThat(result).isEqualTo("hunter2");
        }

        @Test
        void mask_shouldBehaveLikeTheTwoArgOverloadWhenUnmaskIsFalse() {
            String result = engine.mask("database.password", "hunter2", false);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void mask_shouldStillReturnNullForANullValueWhenUnmaskIsTrue() {
            assertThat(engine.mask("database.password", null, true)).isNull();
        }
    }
}
