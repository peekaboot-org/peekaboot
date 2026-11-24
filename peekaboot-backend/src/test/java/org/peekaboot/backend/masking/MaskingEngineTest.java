package org.peekaboot.backend.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MaskingEngineTest {

    private final MaskingEngine engine = new MaskingEngine();

    @Nested
    class KeyNameRulesPositive {

        // One realistic property/header-shaped key per key-name rule, each embedding the
        // rule term at a separator boundary.
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
        void isSensitiveKeyMatchesEachKeyNameRule(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        // A @ConfigurationProperties group or an env-var prefix is often named in the
        // plural (app.secrets.*, DB_PASSWORDS). Matching is whole-token, so the singular
        // rule never sees "secrets": each plural is listed as a rule of its own. The last
        // three rows are the cost, accepted the same way as XDG_SESSION_ID below: "tokens"
        // and "secrets" also name an LLM budget or a Kubernetes secrets mount, none of them
        // secret, and a masked number on the Config tab costs less than a leaked subtree.
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "passwords",
                    "app.passwds",
                    "app.passphrases.primary",
                    "app.secrets.db",
                    "app.tokens.github",
                    "app.api-keys",
                    "apikeys",
                    "app.access-keys",
                    "app.private-keys",
                    "app.secret-keys",
                    "app.signing-keys.current",
                    "app.encryption-keys.primary",
                    "app.client-secrets",
                    "DB_PASSWORDS",
                    "spring.ai.openai.chat.options.max-tokens",
                    "maxTokens",
                    "spring.cloud.kubernetes.secrets.enabled",
                })
        void isSensitiveKeyMatchesThePluralOfARuleWord(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        // The three environment variables a developer meets on the systemEnvironment
        // property source that the rules catch without being secrets themselves; see
        // MaskingRules.KEY_NAME_EXCEPTIONS for why none of them is exempted.
        @ParameterizedTest
        @ValueSource(strings = {"XDG_SESSION_ID", "SSH_AUTH_SOCK", "CREDENTIALS_DIRECTORY"})
        void isSensitiveKeyMatchesTheSensitiveAdjacentEnvironmentVariables(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "DB_PASS",
                    "smtp.pass",
                    "db.pw",
                    "app.passcode",
                    "app.passcodes",
                    "spring.cloud.azure.storage.account-key",
                    "AccountKey",
                    "app.account-keys",
                    "app.master-key",
                    "MASTER_KEYS",
                    "sentry.dsn",
                    "SENTRY_DSN",
                    "encrypt.key",
                    "ENCRYPT_KEY",
                    "jwt",
                    "JWT",
                })
        void isSensitiveKeyMatchesTheAbbreviatedAndProviderKeyNames(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        // The accepted cost of bare "pass", like max-tokens above: it also names a pass-through.
        @Test
        void isSensitiveKeyMatchesPassWhereItNamesNoPassword() {
            assertThat(engine.isSensitiveKey("app.pass-through.enabled")).isTrue();
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
        void isSensitiveKeyMatchesEachLegacySpringPattern(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }
    }

    @Nested
    class KeyNameRulesNegative {

        // Cases the dashboard must not over-mask.
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
        void isSensitiveKeyDoesNotMatchNegativeCases(String key) {
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
        void isSensitiveKeyDoesNotMatchCookieConfigurationKeys(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }

        // "password" must not match inside "passwordless" - there is no boundary between
        // them, so this pins that the whole-token check (both tokenizations) rejects a
        // partial match rather than falling back to a substring check.
        @ParameterizedTest
        @ValueSource(strings = {"passwordless", "app.passwordless.enabled"})
        void isSensitiveKeyDoesNotMatchPasswordAsAPrefixOfALongerWord(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }

        // "oauth2" must not match the bare "auth" rule - it is one token, not "auth"
        // followed by a separator, so a whole-token check must reject it.
        @Test
        void isSensitiveKeyDoesNotMatchAuthAsASubstringOfOauth2() {
            assertThat(engine.isSensitiveKey("spring.security.oauth2.client.registration.google.client-id"))
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "compass.heading",
                    "app.bypass-proxy",
                    "app.passport-number",
                    "pwm.frequency",
                    "app.dsname",
                    "spring.security.oauth2.resourceserver.jwt.audiences",
                    "app.jwt.clock-skew",
                    "encrypt.key-store.location",
                    "encrypt.key-store.alias",
                })
        void isSensitiveKeyDoesNotMatchWordsAndGroupsAroundTheNewRules(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }
    }

    /**
     * A key whose last token is "uri" or "url" names where something lives, not a secret.
     * The OAuth2 provider endpoints are the case that matters: they are public addresses
     * and among the first properties read when a login misbehaves.
     */
    @Nested
    class EndpointKeys {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "spring.security.oauth2.client.provider.keycloak.token-uri",
                    "spring.security.oauth2.client.provider.keycloak.authorization-uri",
                    "app.tokenUri",
                    "app.TOKEN_URL",
                    "app.api-key-url",
                })
        void isSensitiveKeyDoesNotJudgeAnAddressKeyByItsName(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }

        // Only the key's last token is waived: a rule word anywhere else still decides,
        // and "uri"/"url" in the middle of a name says nothing about the value.
        @ParameterizedTest
        @ValueSource(strings = {"token", "authorization", "app.token-uri.password", "app.url-token"})
        void isSensitiveKeyStillMatchesAKeyThatDoesNotEndInAnAddress(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        // The legacy Spring patterns match the whole key, not its vocabulary: a
        // vcap.services binding's URI carries that binding's own password.
        @Test
        void isSensitiveKeyStillMatchesAVcapServicesBindingUri() {
            assertThat(engine.isSensitiveKey("vcap.services.mydb.credentials.uri"))
                    .isTrue();
        }

        // Only the name is exempt; a credential inside such a URL is still caught by its
        // shape, exactly as under any other innocuous key.
        @Test
        void maskStillMasksAnAccessTokenCarriedInsideAnAddressKeysValue() {
            String masked = engine.mask(
                    "spring.security.oauth2.client.provider.keycloak.token-uri",
                    "https://idp.example.com/realms/acme/protocol/openid-connect/token?access_token=s3cr3tvalue");

            assertThat(masked)
                    .isEqualTo("https://idp.example.com/realms/acme/protocol/openid-connect/token?access_token=******");
        }
    }

    @Nested
    class KeySeparatorStyles {

        @ParameterizedTest
        @ValueSource(strings = {"api-key", "API_KEY", "api.key", "Api-Key"})
        void isSensitiveKeyMatchesRegardlessOfSeparatorStyle(String key) {
            assertThat(engine.isSensitiveKey(key)).isTrue();
        }

        // "clientSecret" has no separator between the words at all - only camelCase
        // boundary detection (not dot/hyphen/underscore splitting) can recognise it.
        // "apiKey" is deliberately not used here: it would pass even without camelCase
        // handling because "apikey" (all lowercase, no separator) is itself a listed rule.
        @Test
        void isSensitiveKeyMatchesACamelCaseCompoundNameWithNoSeparator() {
            assertThat(engine.isSensitiveKey("clientSecret")).isTrue();
        }

        // The PWD exception is for the shell's variable, which is upper-case by POSIX
        // convention; a bare lower-case "pwd" is the JDBC/form-field spelling of a
        // password (";pwd=" on a SQL Server URL, "?pwd=" on a login form) and masks.
        @Test
        void isSensitiveKeyExemptsOnlyTheShellsUpperCaseSpellingOfPwd() {
            assertThat(engine.isSensitiveKey("PWD")).isFalse();
            assertThat(engine.isSensitiveKey("pwd")).isTrue();
        }

        @Test
        void isSensitiveKeyReturnsFalseForNullKey() {
            assertThat(engine.isSensitiveKey(null)).isFalse();
        }

        @Test
        void isSensitiveKeyReturnsFalseForBlankKey() {
            assertThat(engine.isSensitiveKey("  ")).isFalse();
        }
    }

    @Nested
    class MixedCaseSpellings {

        // A camelCase-boundary tokenizer alone would mis-split these: "CredenTials" becomes
        // ["creden", "tials"], neither of which is the rule token "credentials". Spring's relaxed
        // binding means the caller does not control how an external property source spells
        // a key, so a rule word with a stray internal capital must still match.
        @Test
        void isSensitiveKeyMatchesAMixedCaseSpellingOfCredentials() {
            assertThat(engine.isSensitiveKey("spring.datasource.CredenTials")).isTrue();
        }

        @Test
        void isSensitiveKeyMatchesAMixedCaseSpellingOfAnotherSingleWordRule() {
            assertThat(engine.isSensitiveKey("app.SeCreT")).isTrue();
        }
    }

    /**
     * A key is matched in NFKD form with its marks and format characters removed, and with each control character
     * read as a separator or removed, so a long s (U+017F), an accent or an invisible character cannot hide a rule
     * word. Matching stays whole-token.
     */
    @Nested
    class KeyNormalisation {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "spring.datasource.pa\u017F\u017Fword",
                    "SPRING_DATASOURCE_PA\u017F\u017FWORD",
                    "spring.datasource.passw\u0000ord",
                    "app.se\u200Bcret",
                    "spring.datasource.passw\uFE0Ford",
                    "spring.datasource.passw\u034Ford",
                    "spring.datasource.pa\u0301ssword",
                    "spring.datasource.p\u00E1ssword",
                    "spring.datasource.\uFF50\uFF41\uFF53\uFF53\uFF57\uFF4F\uFF52\uFF44"
                })
        void maskHidesAPropertyWhoseKeyHidesARuleWord(String key) {
            assertThat(engine.mask(key, "S3Cx")).isEqualTo("******");
        }

        // A control character reads as a separator too, as it did before normalisation: removing it alone glues a
        // rule word to its neighbour ("mypassword") or, for PWD<NUL>, makes the exempt shell variable's spelling.
        @ParameterizedTest
        @ValueSource(strings = {"app.my\tpassword", "spring.datasource.password\tx", "app.token\u0000ize", "PWD\u0000"})
        void maskHidesAPropertyWhoseControlCharacterSeparatesARuleWord(String key) {
            assertThat(engine.mask(key, "S3Cx")).isEqualTo("******");
        }

        @Test
        void maskValueHidesAUrlParameterWhoseNameHidesARuleWordAndKeepsTheNameAsWritten() {
            assertThat(engine.maskValue("jdbc:postgresql://db/shop?pa\u017F\u017Fword=S3Cx&user=app"))
                    .isEqualTo("jdbc:postgresql://db/shop?pa\u017F\u017Fword=******&user=app");
        }

        @Test
        void maskValueHidesAUrlParameterWhoseControlCharacterSeparatesARuleWord() {
            assertThat(engine.maskValue("jdbc:postgresql://db/shop?token\u0000ize=S3Cx&user=app"))
                    .isEqualTo("jdbc:postgresql://db/shop?token\u0000ize=******&user=app");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "pa\u017F\u017Fwordle\u017F\u017F",
                    "app.pass\u200Bwordless.enabled",
                    "app.pa\u0301sswordless.enabled"
                })
        void isSensitiveKeyKeepsPasswordlessReadableAfterNormalisation(String key) {
            assertThat(engine.isSensitiveKey(key)).isFalse();
        }
    }

    @Nested
    class ValuePatterns {

        @Test
        void maskValueMasksJwt() {
            String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0"
                    + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
            String value = "Authorization header carried: " + jwt;

            String result = engine.maskValue(value);

            assertThat(result).doesNotContain(jwt).contains("******").startsWith("Authorization header carried: ");
        }

        @Test
        void maskValueMasksPemPrivateKey() {
            String value = "-----BEGIN RSA PRIVATE KEY-----";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValueMasksPemPrivateKeyBody() {
            String body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDb\n".repeat(5);
            String value = "-----BEGIN RSA PRIVATE KEY-----\n" + body + "-----END RSA PRIVATE KEY-----";

            assertThat(engine.maskValue(value)).isEqualTo("******").doesNotContain("MIIEvQIBADAN");
        }

        @Test
        void maskValueMasksEachOfTwoAdjacentPemKeysSeparately() {
            String value = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
                    + " between "
                    + "-----BEGIN PRIVATE KEY-----\nBBBB\n-----END PRIVATE KEY-----";

            assertThat(engine.maskValue(value)).isEqualTo("****** between ******");
        }

        @Test
        void maskValueMasksToEndOfValueWhenPemFooterIsMissing() {
            String value = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEIBt3\n";

            assertThat(engine.maskValue(value)).isEqualTo("******");
        }

        @Test
        void maskValueMasksAwsAccessKey() {
            // AWS's own documentation placeholder, not a real key.
            String value = "aws_access_key_id=AKIAIOSFODNN7EXAMPLE";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("aws_access_key_id=******");
        }

        @Test
        void maskValueMasksGithubClassicToken() {
            String value = "ghp_16C7e42F292c6912E7710c838347Ae178B4a";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValueMasksGithubFineGrainedToken() {
            String value =
                    "github_pat_11AAAAAAA0abcdefghijkl_" + "MNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890AB";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValueMasksGcpApiKey() {
            // Obviously fake, unlike a real GCP key: EXAMPLE plus zero-padding.
            String value = "key=AIzaEXAMPLE0000000000000000000000000000";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("key=******");
        }

        @Test
        void maskValueMasksSlackToken() {
            // Split literal, deliberately: this file is full of strings shaped exactly
            // like the credentials the engine detects, and GitHub's push protection scans
            // for the same shapes. Concatenation is folded at compile time, so the value
            // under test is unchanged; it just never appears contiguously in the source.
            String value = "xoxb" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValueMasksStripeLiveKey() {
            // Split literal, deliberately: this file is full of strings shaped exactly
            // like the credentials the engine detects, and GitHub's push protection scans
            // for the same shapes. Concatenation is folded at compile time, so the value
            // under test is unchanged; it just never appears contiguously in the source.
            String value = "sk_live" + "_4eC39HqLyjWDarjtT1zdp7dcEXAMPLE";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValueMasksOpenAiProjectKey() {
            String value = "sk-proj-EXAMPLEabcdefghijklmnopqrstuvwxyz1234567890";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskValueMasksAnthropicKey() {
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
        void maskValueMasksLegacyOpenAiKey() {
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
        void maskValueMasksAnOpenAiProjectKeyExactlyOnceWithTheLegacyPatternAlsoActive() {
            String key = "sk-proj-" + "A".repeat(40);
            String value = "key=" + key + "&ok=1";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("key=******&ok=1");
        }

        /**
         * Two different rules claiming overlapping spans: the userinfo rule wants
         * {@code ci:ghp_...} and the GitHub rule wants the token inside it. The inner span
         * is merged into the outer one, so the value masks once rather than stacking two
         * masks or leaving a fragment of the token behind.
         */
        @Test
        void maskValueMasksAnOverlappingSpanOnceWhenTwoRulesClaimIt() {
            String token = "ghp_" + "16C7e42F292c6912E7710c838347Ae178B4a";
            String value = "https://ci:" + token + "@github.example.com/org/repo.git";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("https://******@github.example.com/org/repo.git");
        }

        // The query rule's value span ends inside the PEM header, and the PEM span runs on past it.
        @Test
        void maskValueMasksAPartialOverlapThroughToTheLaterSpansEnd() {
            String value = "https://h/x?secret=abc-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY----- tail";

            assertThat(engine.maskValue(value)).isEqualTo("https://h/x?secret=****** tail");
        }

        // The quoted-option span holds the URL's userinfo span, which ends earlier: the outer end stands.
        @Test
        void maskValueKeepsTheOuterSpanWhereALaterOneEndsInsideIt() {
            assertThat(engine.maskValue("password=\"https://u:p@h/x\" ok")).isEqualTo("password=\"******\" ok");
        }

        // The GitHub rule's span and the query rule's start together; the query value runs on past the token.
        @Test
        void maskValueMasksToTheLongerOfTwoSpansThatStartTogether() {
            String token = "ghp_" + "16C7e42F292c6912E7710c838347Ae178B4a";

            assertThat(engine.maskValue("https://h/x?token=" + token + ".rest&ok=1"))
                    .isEqualTo("https://h/x?token=******&ok=1");
        }

        // The second key starts where the first one ends: nothing overlaps, so each has its own mask.
        @Test
        void maskValueMasksTwoSpansThatOnlyTouchSeparately() {
            String key = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----";

            assertThat(engine.maskValue(key + key)).isEqualTo("************");
        }

        @Test
        void maskValueMasksOnlyUserinfoInJdbcUrl() {
            String value = "jdbc:postgresql://dbuser:S3cr3tPassw0rd@localhost:5432/mydb";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("jdbc:postgresql://******@localhost:5432/mydb");
            assertThat(result).contains("localhost", "5432", "mydb").doesNotContain("dbuser", "S3cr3tPassw0rd");
        }

        // A scheme is case-insensitive (RFC 3986), and a JDBC URL pasted from a Windows
        // tool or an upper-cased env var spells it that way.
        @Test
        void maskValueMasksUserinfoBehindAnUpperCaseScheme() {
            String value = "JDBC:POSTGRESQL://dbuser:S3cr3tPassw0rd@localhost:5432/mydb";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("JDBC:POSTGRESQL://******@localhost:5432/mydb");
        }

        /** The common Redis shape: no user at all, only a password in front of the '@'. */
        @Test
        void maskValueMasksUserinfoWithAnEmptyUser() {
            String value = "redis://:S3cr3tPassw0rd@cache.example.com:6379/0";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("redis://******@cache.example.com:6379/0");
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "redis://user:pa:ss@cache.example.com:6379/0        | redis://******@cache.example.com:6379/0",
                    "amqp://user:pa/ss@mq.example.com:5672/vhost         | amqp://******@mq.example.com:5672/vhost",
                    "https://user:p@ss@host.example.com/path             | https://******@host.example.com/path",
                    "smtp://bob@example.com:S3cr3t@smtp.example.com:587  | smtp://******@smtp.example.com:587",
                    "http://user:pass@[::1]:8080/x                       | http://******@[::1]:8080/x",
                    "https://user:pass@host/path?email=bob@example.com   | https://******@host/path?email=bob@example.com",
                    "https://user:pa?ss@host.example.com/path            | https://******@host.example.com/path",
                    "postgres://app:pa#ss@db.example.com:5432/app        | postgres://******@db.example.com:5432/app"
                })
        void maskValueMasksUserinfoWhosePasswordHoldsUrlDelimiters(String value, String masked) {
            assertThat(engine.maskValue(value)).isEqualTo(masked);
        }

        /** Digits and a delimiter are a port only when no '@' follows before the next '/', ':' or space. */
        @ParameterizedTest
        @ValueSource(chars = {'?', '#', ',', ';', '"', '\'', '<', '>', ')', ']', '}'})
        void maskValueMasksAPasswordThatStartsWithDigitsAndADelimiter(char delimiter) {
            String value = "amqp://user:5672" + delimiter + "S3cr3t@mq.example.com:5672/vhost";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("amqp://******@mq.example.com:5672/vhost");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://example.com:8080/users/bob@example.com",
                    "https://example.com:8080?next=/users/bob@example.com",
                    "http://[::1]:8080/users/bob@example.com",
                    "ssh://git@github.com:22/a@b",
                    "mongodb://h1:27017,h2:27017/db?appName=a@b",
                    "{\"url\":\"http://host:8080\",\"email\":\"bob@example.com\"}",
                    "{\"homepage\":\"https://example.com\",\"contact\":\"bob@example.com\"}",
                    "{\"u\":\"http://h\",\"t\":\"12:30\",\"e\":\"a@b\"}"
                })
        void maskValueLeavesAUrlWithoutUserinfoAloneWhateverFollowsIt(String value) {
            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        /**
         * Oracle's thin URL puts user/password in front of the '@' with no "://" ahead of
         * them, so the generic userinfo rule never sees it; only the password is masked.
         */
        @Test
        void maskValueMasksOnlyThePasswordInAnOracleThinUrl() {
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
        void maskValueMasksOnlyThePasswordValueInAJdbcUrlQueryParameter() {
            String value = "jdbc:mysql://localhost:3306/mydb?user=root&password=hunter2";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("jdbc:mysql://localhost:3306/mydb?user=root&password=******");
            assertThat(result)
                    .contains("localhost", "3306", "mydb", "user=root")
                    .doesNotContain("hunter2");
        }

        @Test
        void maskValueMasksAnApiKeyInAUrlQueryParameter() {
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
        void maskValueMasksEachUrlQueryCredentialParameterName(String paramName) {
            String value = "https://example.com/callback?" + paramName + "=s3cr3t&ok=1";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("https://example.com/callback?" + paramName + "=******&ok=1");
        }

        /** The shape a client span's {@code http.url} tag carries for a token exchange. */
        @Test
        void maskValueMasksOnlyTheSensitivePairsInAUrlWithSeveralParameters() {
            String value =
                    "https://oauth.example.com/token?client_id=abc&client_secret=s3cr3t&grant_type=client_credentials";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo(
                            "https://oauth.example.com/token?client_id=abc&client_secret=******&grant_type=client_credentials");
        }

        /** SQL Server's JDBC URL separates its properties with semicolons rather than ?/&. */
        @Test
        void maskValueMasksASemicolonDelimitedJdbcProperty() {
            String value =
                    "jdbc:sqlserver://db.example.com:1433;databaseName=orders;user=app;password=hunter2;encrypt=true";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo(
                            "jdbc:sqlserver://db.example.com:1433;databaseName=orders;user=app;password=******;encrypt=true");
        }

        @Test
        void maskValueDoesNotMaskAnOrdinaryQueryParameter() {
            String value = "https://example.com/search?query=widgets&page=2";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        // Bare "key" is deliberately not a key-name rule (see MaskingRules); the query
        // rule inherits that decision rather than keeping a vocabulary of its own.
        @Test
        void maskValueLeavesABareKeyQueryParameterAlone() {
            String value = "https://maps.example.com/geocode?key=abc123&address=Berlin";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        /** The shape of an Azure SAS URL: only {@code sig} carries the secret. */
        @Test
        void maskValueMasksOnlyTheSigParameterOfAnAzureSasUrl() {
            String value = "https://acct.blob.core.windows.net/c/blob.txt?sv=2024-11-04&se=2026-01-01&sig=s3cr3t";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo("https://acct.blob.core.windows.net/c/blob.txt?sv=2024-11-04&se=2026-01-01&sig=******");
        }

        // "sig" matches as an exact token only, never as a prefix of a longer word.
        @Test
        void maskValueDoesNotMaskQueryParametersMerelyStartingWithSig() {
            String value = "https://example.com/render?design=flat&signal=9&sigma=0.5";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        // The PWD exemption is for the shell variable as a whole key; a parameter name
        // inside a value (an ODBC connection string's ";PWD=") is judged without it.
        @Test
        void maskValueMasksAnUpperCasePwdParameterInsideAValue() {
            assertThat(engine.maskValue("Server=db;UID=sa;PWD=hunter2")).isEqualTo("Server=db;UID=sa;PWD=******");
            assertThat(engine.maskValue("https://example.com/login?PWD=hunter2"))
                    .isEqualTo("https://example.com/login?PWD=******");
        }

        @Test
        void maskValueMasksAnAzureStorageAccountKeyInAConnectionString() {
            String value =
                    "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=abc123==;EndpointSuffix=core.windows.net";

            String result = engine.maskValue(value);

            assertThat(result)
                    .isEqualTo(
                            "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=******;EndpointSuffix=core.windows.net");
        }

        @Test
        void maskMasksAQuotedPasswordInAJaasConfig() {
            String value = "org.apache.kafka.common.security.plain.PlainLoginModule required"
                    + " username=\"alice\" password=\"alice-secret\";";

            String result = engine.mask("spring.kafka.properties.sasl.jaas.config", value);

            assertThat(result)
                    .isEqualTo("org.apache.kafka.common.security.plain.PlainLoginModule required"
                            + " username=\"alice\" password=\"******\";");
        }

        @Test
        void maskValueMasksAQuotedValueThroughAnEscapedQuote() {
            String value = "x required password=\"ab\\\"cd\" mechanism=\"PLAIN\";";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required password=\"******\" mechanism=\"PLAIN\";");
        }

        @Test
        void maskValueMasksAQuotedValueThatEndsInAnEscapedBackslash() {
            String value = "x required username=\"alice\" password=\"abc\\\\\";";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required username=\"alice\" password=\"******\";");
        }

        @Test
        void maskValueEndsAQuotedValueAtTheQuoteAfterAnEscapedBackslash() {
            String value = "x required password=\"abc\\\\\" user=\"u\";";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required password=\"******\" user=\"u\";");
        }

        @Test
        void maskValueMasksAQuotedValueThroughAnEscapedBackslashAndAnEscapedQuote() {
            String value = "x required password=\"ab\\\\\\\"cd\" mechanism=\"PLAIN\";";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required password=\"******\" mechanism=\"PLAIN\";");
        }

        @Test
        void maskValueMasksAQuotedValueWithNoClosingQuoteToTheEnd() {
            String value = "x required username=\"alice\" password=\"correct horse";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required username=\"alice\" password=\"******");
        }

        @Test
        void maskValueMasksAnUnclosedQuotedValueThroughAnEscapedQuoteAtItsEnd() {
            String value = "x required password=\"correct horse\\\"";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required password=\"******");
        }

        @Test
        void maskValueMasksAnUnclosedQuotedValueThatEndsInABackslash() {
            String value = "x required password=\"correct horse\\";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("x required password=\"******");
        }

        @Test
        void maskValueLeavesAQuotedOptionWithAnInnocuousNameAlone() {
            String value = "x required username=\"alice\" mechanism=\"PLAIN\";";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "ASIA" + "IOSFODNN7EXAMPLE",
                    "ABIA" + "IOSFODNN7EXAMPLE",
                    "ACCA" + "IOSFODNN7EXAMPLE",
                    "A3TX" + "IOSFODNN7EXAMPLE",
                    "gho" + "_16C7e42F292c6912E7710c838347Ae178B4a",
                    "ghu" + "_16C7e42F292c6912E7710c838347Ae178B4a",
                    "ghs" + "_16C7e42F292c6912E7710c838347Ae178B4a",
                    "ghr" + "_16C7e42F292c6912E7710c838347Ae178B4a",
                    "xoxa" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx",
                    "xoxp" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx",
                    "xoxr" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx",
                    "xoxs" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx",
                    "rk_live" + "_4eC39HqLyjWDarjtT1zdp7dcEXAMPLE",
                })
        void maskValueMasksEveryProviderPrefixTheRulesName(String value) {
            assertThat(engine.maskValue(value)).isEqualTo("******");
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
        void maskQueryStringMasksSensitivePairsAndLeavesTheRestAlone() {
            assertThat(engine.maskQueryString("api_key=xyz&q=widgets")).isEqualTo("api_key=******&q=widgets");
        }

        @Test
        void maskQueryStringPreservesAPairWithNoValue() {
            assertThat(engine.maskQueryString("debug&q=widgets")).isEqualTo("debug&q=widgets");
        }

        @Test
        void maskQueryStringReturnsNullAndBlankInputUnchanged() {
            assertThat(engine.maskQueryString(null)).isNull();
            assertThat(engine.maskQueryString(" ")).isEqualTo(" ");
        }

        /** The key is judged decoded, so an encoded spelling of a sensitive name still masks. */
        @Test
        void maskQueryStringDecodesTheKeyBeforeJudgingIt() {
            assertThat(engine.maskQueryString("api%5Fkey=xyz&q=a%20b")).isEqualTo("api_key=******&q=a+b");
        }

        /**
         * A pair with a malformed percent sequence cannot be decoded, so it cannot be
         * judged: its value is masked and the rest of the string survives, rather than
         * the whole request capture being lost.
         */
        @Test
        void maskQueryStringToleratesAMalformedPercentSequence() {
            assertThat(engine.maskQueryString("q=100%")).isEqualTo("q=******");
            assertThat(engine.maskQueryString("a=%zz&password=x")).isEqualTo("a=******&password=******");
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
        void maskValueMasksTheValueOfASystemPropertyOptionWithASensitiveKey() {
            String value = "-Xmx1g -Dspring.datasource.password=hunter2 -Dserver.port=8080";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("-Xmx1g -Dspring.datasource.password=****** -Dserver.port=8080");
        }

        @Test
        void maskValueMasksTheValueOfADoubleDashOptionWithASensitiveKey() {
            String value = "--spring.datasource.password=hunter2 --server.port=8080";

            String result = engine.maskValue(value);

            assertThat(result).isEqualTo("--spring.datasource.password=****** --server.port=8080");
        }

        @Test
        void maskMasksOptionValuesInsideAnInnocuouslyNamedEnvironmentVariable() {
            String result = engine.mask("JAVA_TOOL_OPTIONS", "-Dapp.api-key=abc123 -Dspring.profiles.active=prod");

            assertThat(result).isEqualTo("-Dapp.api-key=****** -Dspring.profiles.active=prod");
        }

        @Test
        void maskValueLeavesOptionsWithInnocuousKeysAlone() {
            String value = "-Dserver.port=8080 -Dspring.profiles.active=local --debug=true";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        // An option only starts at a word boundary: "-D" glued onto a preceding token is
        // part of that token (a path segment, an identifier), not a JVM flag.
        @Test
        void maskValueDoesNotTreatADashDInsideAWordAsAnOption() {
            String value = "build-Dpassword=1";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }
    }

    @Nested
    class ValuesThatSurviveUntouched {

        @Test
        void maskValueDoesNotTouchAGitSha() {
            String value = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValueDoesNotTouchAUuid() {
            String value = "550e8400-e29b-41d4-a716-446655440000";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValueDoesNotTouchABase64EncodedAsset() {
            String value = "aGVsbG8gd29ybGQgdGhpcyBpcyBhIGJhc2U2NCBlbmNvZGVkIHN0cmluZw==";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValueDoesNotTouchAnIsoTimestamp() {
            String value = "2026-08-23T15:17:00Z";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValueDoesNotTouchAPlainUrlWithNoCredentials() {
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
        void maskValueDoesNotTouchAnSkPrefixedInfraIdentifier() {
            String value = "sk-cluster-prod-eu-west-1a-worker-nodes";

            assertThat(engine.maskValue(value)).isEqualTo(value);
        }

        @Test
        void maskValueReturnsNullForNullValue() {
            assertThat(engine.maskValue(null)).isNull();
        }

        @Test
        void maskValueReturnsEmptyStringForEmptyValue() {
            assertThat(engine.maskValue("")).isEmpty();
        }
    }

    @Nested
    class MaskKeyValuePair {

        @Test
        void maskReplacesWholeValueWhenKeyIsSensitive() {
            String result = engine.mask("database.password", "hunter2");

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskLeavesValueUntouchedWhenNeitherKeyNorValueIsSensitive() {
            String result = engine.mask("server.port", "8080");

            assertThat(result).isEqualTo("8080");
        }

        @Test
        void maskMasksOnlyTheCredentialSpanWhenKeyIsInnocuousButValueIsNot() {
            String url = "jdbc:postgresql://dbuser:S3cr3tPassw0rd@localhost:5432/mydb";

            String result = engine.mask("spring.datasource.url", url);

            assertThat(result).isEqualTo("jdbc:postgresql://******@localhost:5432/mydb");
        }

        @Test
        void maskReturnsNullWhenValueIsNull() {
            assertThat(engine.mask("database.password", null)).isNull();
        }

        @Test
        void maskFallsBackToValueRulesWhenKeyIsNull() {
            String result = engine.mask(null, "aws_access_key_id=AKIAIOSFODNN7EXAMPLE");

            assertThat(result).isEqualTo("aws_access_key_id=******");
        }

        @Test
        void maskTreatsAHeaderNameLikeAnyOtherKey() {
            String result = engine.mask("X-Api-Key", "abc123");

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskLeavesAnInnocuousHeaderUntouched() {
            String result = engine.mask("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000");

            assertThat(result).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        }

        // Narrowing cookie/set-cookie to whole-key matches must not stop them masking
        // the actual HTTP headers they exist for.
        @Test
        void maskMasksTheCookieHeader() {
            String result = engine.mask("Cookie", "session=abc123");

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskMasksTheSetCookieHeader() {
            String result = engine.mask("Set-Cookie", "session=abc123; Path=/; HttpOnly");

            assertThat(result).isEqualTo("******");
        }

        // The OpenTelemetry header-capture attributes carry the same two headers on a span.
        @Test
        void maskMasksTheHeaderCaptureAttributesOfTheCookieHeaders() {
            assertThat(engine.mask("http.request.header.cookie", "session=abc123"))
                    .isEqualTo("******");
            assertThat(engine.mask("http.response.header.set-cookie", "session=abc123; Path=/"))
                    .isEqualTo("******");
        }

        // The one new property this feature adds whose value is a live credential on a deployed
        // application's Config tab; pinned by name rather than relying on the generic "password"
        // rule to keep covering it.
        @Test
        void maskNeverSurfacesThePeekabootSecurityPasswordValue() {
            String result = engine.mask("peekaboot.security.password", "hunter2");

            assertThat(result).isEqualTo("******").doesNotContain("hunter2");
        }
    }

    @Nested
    class MaskPropertyWithUnmaskFlag {

        @Test
        void maskPropertyReturnsValueVerbatimWhenUnmaskIsTrueEvenForASensitiveKey() {
            String result = engine.maskProperty("database.password", "hunter2", true);

            assertThat(result).isEqualTo("hunter2");
        }

        @Test
        void maskPropertyMasksASensitiveKeyWhenUnmaskIsFalse() {
            String result = engine.maskProperty("database.password", "hunter2", false);

            assertThat(result).isEqualTo("******");
        }

        @Test
        void maskPropertyStillReturnsNullForANullValueWhenUnmaskIsTrue() {
            assertThat(engine.maskProperty("database.password", null, true)).isNull();
        }

        @Test
        void maskPropertyReturnsAUrlValueVerbatimWhenUnmaskIsTrue() {
            String url = "jdbc:postgresql://db/shop?sslpassword=S3Cx";

            assertThat(engine.maskProperty("spring.datasource.url", url, true)).isEqualTo(url);
        }
    }

    /**
     * A URL parameter's value runs to the next separator of its own kind: a ";" param's value may hold "&", a "?" or
     * "&" param's value may hold ";", and a braced ";" value runs to its closing brace, as SQL Server reads it.
     */
    @Nested
    class UrlParameterValueBoundaries {

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "jdbc:sqlserver://db;password=ab&cd;databaseName=x|jdbc:sqlserver://db;password=******;databaseName=x",
                    "jdbc:h2:mem:shop;PASSWORD=ab&cd|jdbc:h2:mem:shop;PASSWORD=******",
                    "jdbc:postgresql://db/shop?password=ab;cd&sslmode=require"
                            + "|jdbc:postgresql://db/shop?password=******&sslmode=require",
                    "jdbc:postgresql://db/shop?user=a;password=S3Cx|jdbc:postgresql://db/shop?user=a;password=******",
                    "GET /x?q=1; token=S3Cx|GET /x?q=1; token=******",
                    "jdbc:sqlserver://db;password={ab;cd};databaseName=x"
                            + "|jdbc:sqlserver://db;password=******;databaseName=x",
                    "jdbc:sqlserver://db;password={ab}};cd&e f};databaseName=x"
                            + "|jdbc:sqlserver://db;password=******;databaseName=x",
                    "jdbc:sqlserver://db;password={ab;cd|jdbc:sqlserver://db;password=******"
                })
        void maskValueMasksAUrlParameterValueUpToItsOwnSeparator(String value, String expected) {
            assertThat(engine.maskValue(value)).isEqualTo(expected);
        }
    }

    /**
     * A URL parameter inside a property value is judged as a JDBC connection param, secret words anywhere in its
     * name included. Every other value, a request path or a log line, keeps the whole-token key rules.
     */
    @Nested
    class UrlParametersInPropertyValues {

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "jdbc:postgresql://db/shop?sslpassword=S3Cx&sslmode=require"
                            + "|jdbc:postgresql://db/shop?sslpassword=******&sslmode=require",
                    "jdbc:postgresql://db/shop?sslmode=require&sslpa\u017F\u017Fword=S3Cx"
                            + "|jdbc:postgresql://db/shop?sslmode=require&sslpa\u017F\u017Fword=******",
                    "jdbc:sqlserver://db:1433;databaseName=shop;keyVaultProviderClientKey=S3Cx"
                            + "|jdbc:sqlserver://db:1433;databaseName=shop;keyVaultProviderClientKey=******",
                    "jdbc:postgresql://db/shop?sslmode=require|jdbc:postgresql://db/shop?sslmode=require"
                })
        void maskPropertyValueJudgesAUrlParameterAsAConnectionParam(String value, String expected) {
            assertThat(engine.maskPropertyValue(value)).isEqualTo(expected);
            assertThat(engine.maskProperty("spring.datasource.url", value, false))
                    .isEqualTo(expected);
        }

        @Test
        void maskValueKeepsWholeTokenRulesForAUrlParameterOutsideAPropertyValue() {
            assertThat(engine.maskValue("/search?keyword=shoes&passengerCount=2"))
                    .isEqualTo("/search?keyword=shoes&passengerCount=2");
        }

        @Test
        void maskPropertyValueKeepsWholeTokenRulesForACommandLineOptionName() {
            assertThat(engine.maskPropertyValue("-Dapp.search.keyword=shoes -Dapp.passengers=2"))
                    .isEqualTo("-Dapp.search.keyword=shoes -Dapp.passengers=2");
        }

        // Every value: a key led by whitespace is still the key.
        @ParameterizedTest
        @ValueSource(strings = {"jdbc:sqlserver://db:1433;\tpassword=S3Cx", "jdbc:sqlserver://db:1433; password=S3Cx"})
        void maskValueReadsAUrlParameterNamePastLeadingWhitespace(String value) {
            assertThat(engine.maskValue(value)).endsWith("password=******");
        }
    }
}
