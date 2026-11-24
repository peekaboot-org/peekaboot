package org.peekaboot.backend.datasource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.peekaboot.backend.masking.MaskingEngine;

/**
 * Prepares a configured JDBC URL for the parser so that no user or password can reach what it reads.
 *
 * <p>A user and password right after the scheme's {@code //} are cut off where the URL holds exactly one
 * {@code @} after it, inside a first host without a {@code \}. No form is kept where the URL holds a
 * character outside ASCII, where any other {@code @} follows that {@code //}, where a property names a user
 * or a secret, or where an opening brace or a {@code %} appears. The health endpoint documents these
 * refusals and asks for the credentials in {@code spring.datasource.username} and
 * {@code spring.datasource.password} instead.
 */
final class CredentialFreeJdbcUrls {

    private static final MaskingEngine MASKING = new MaskingEngine();
    // only the "//" (Oracle: "@//") right after the scheme opens an authority; H2 and SQLite paths hold "//" too
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*(?::[A-Za-z0-9+.-]+)*:@?//");
    private static final String FIRST_HOST_ENDS = "/,?;#(";
    private static final Pattern PROPERTY_KEY = Pattern.compile("[?;&(,\\s]\\s*([^=?;&(,\\s]+)\\s*=");
    // as parts of a key too, so sslpassword, password1 and a lower-case keystoresecret count
    private static final List<String> CREDENTIAL_KEY_PARTS = List.of("user", "uid", "pwd", "pass", "secret", "token");

    private CredentialFreeJdbcUrls() {}

    static Optional<String> of(String configuredUrl) {
        return Optional.of(configuredUrl)
                .filter(CredentialFreeJdbcUrls::isAscii)
                .flatMap(CredentialFreeJdbcUrls::withoutUserInfo)
                .filter(CredentialFreeJdbcUrls::holdsNoQuotedOrEncodedText)
                .filter(CredentialFreeJdbcUrls::namesNoCredentialProperty);
    }

    // the driver folds a long s (U+017F) to "s", so a key the credential check cannot read may still be one
    private static boolean isAscii(String url) {
        return url.chars().allMatch(c -> c < 0x80);
    }

    // the parser rejects a user before the host as pgjdbc, mssql-jdbc and ojdbc do, so only a cut URL is described
    private static Optional<String> withoutUserInfo(String url) {
        Matcher scheme = SCHEME.matcher(url);
        if (!scheme.lookingAt()) {
            return Optional.of(url);
        }
        int firstHost = scheme.end();
        int at = url.indexOf('@', firstHost);
        if (at < 0) {
            return Optional.of(url);
        }
        // the health contract refuses any other "@" after the "//"
        boolean onlyAt = url.indexOf('@', at + 1) < 0;
        int firstHostEnd = endOfFirstHost(url, firstHost);
        boolean insideFirstHost = at < firstHostEnd;
        // in a SQL Server host\instance the "@" belongs to the instance name
        boolean namesNoInstance = url.substring(firstHost, firstHostEnd).indexOf('\\') < 0;
        return onlyAt && insideFirstHost && namesNoInstance
                ? Optional.of(url.substring(0, firstHost) + url.substring(at + 1))
                : Optional.empty();
    }

    private static int endOfFirstHost(String url, int firstHost) {
        for (int i = firstHost; i < url.length(); i++) {
            if (FIRST_HOST_ENDS.indexOf(url.charAt(i)) >= 0) {
                return i;
            }
        }
        return url.length();
    }

    // the health contract refuses quoted and percent-encoded values; Connector/J reads "p%61ssword" as "password"
    private static boolean holdsNoQuotedOrEncodedText(String url) {
        return url.indexOf('{') < 0 && url.indexOf('%') < 0;
    }

    // the health contract refuses credentials in the URL; they belong in spring.datasource.username and password
    private static boolean namesNoCredentialProperty(String url) {
        Matcher key = PROPERTY_KEY.matcher(url);
        while (key.find()) {
            if (namesACredential(key.group(1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean namesACredential(String key) {
        String lowerCaseKey = key.toLowerCase(Locale.ROOT);
        return MASKING.isSensitiveKey(key) || CREDENTIAL_KEY_PARTS.stream().anyMatch(lowerCaseKey::contains);
    }
}
