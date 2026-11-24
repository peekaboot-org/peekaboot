package org.peekaboot.backend.datasource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.masking.MaskingEngine;

/**
 * Parses a configured JDBC URL only where no host, database or instance it names can hold a user or password.
 *
 * <p>A user and password right after the scheme's {@code //} are cut off where the URL holds exactly one
 * {@code @} after it, inside a first host without a {@code \}. No form is kept where the URL holds a
 * character outside ASCII, where any other {@code @} follows that {@code //}, where a property names a user
 * or a secret, or where an opening brace or a {@code %} appears. A URL the parser rejects as its driver would
 * is refused too, as is a SQL Server URL with an {@code &}, a database or instance that is no plain name or
 * file path, and a database, instance or host the configured URL does not spell out ({@code localhost}, the
 * parser's default host, excepted). The health endpoint documents these refusals and asks for the credentials
 * in {@code spring.datasource.username} and {@code spring.datasource.password} instead.
 */
final class CredentialFreeJdbcUrls {

    private static final MaskingEngine MASKING = new MaskingEngine();
    // only the "//" (Oracle: "@//") right after the scheme opens an authority; H2 and SQLite paths hold "//" too
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*(?::[A-Za-z0-9+.-]+)*:@?//");
    private static final String FIRST_HOST_ENDS = "/,?;#(";
    private static final Pattern PROPERTY_KEY = Pattern.compile("[?;&(,\\s]\\s*([^=?;&(,\\s]+)\\s*=");
    // as parts of a key too, so sslpassword, password1 and a lower-case keystoresecret count
    private static final List<String> CREDENTIAL_KEY_PARTS = List.of("user", "uid", "pwd", "pass", "secret", "token");
    // "/" for the file paths of H2, Derby, HSQLDB and SQLite, "~" for H2's home directory; empty where
    // the URL names no database
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9._$~/-]*");
    private static final String DEFAULT_HOSTNAME = "localhost";

    private CredentialFreeJdbcUrls() {}

    static Optional<JdbcUrl> parse(String configuredUrl) {
        return Optional.of(configuredUrl)
                .filter(CredentialFreeJdbcUrls::isAscii)
                .flatMap(CredentialFreeJdbcUrls::withoutUserInfo)
                .filter(CredentialFreeJdbcUrls::holdsNoQuotedOrEncodedText)
                .filter(CredentialFreeJdbcUrls::namesNoCredentialProperty)
                .flatMap(cutUrl -> parseNamesItSpellsOut(cutUrl, configuredUrl));
    }

    private static Optional<JdbcUrl> parseNamesItSpellsOut(String cutUrl, String configuredUrl) {
        // a cut-off user or password must not vouch for a name, nor may the cut join two parts into one
        Predicate<String> spelledOut = name -> spellsOut(cutUrl, name) && spellsOut(configuredUrl, name);
        return Optional.ofNullable(JdbcUrlParser.tryParse(cutUrl))
                .filter(jdbcUrl -> !isSqlServerWithAmpersand(jdbcUrl, configuredUrl))
                .filter(jdbcUrl -> namesPlainDatabaseAndInstance(jdbcUrl, spelledOut))
                .filter(jdbcUrl -> spellsOutEveryHostname(jdbcUrl, spelledOut));
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

    // the health contract refuses an "&" in a SQL Server URL, where it can only sit inside a value
    private static boolean isSqlServerWithAmpersand(JdbcUrl jdbcUrl, String configuredUrl) {
        return jdbcUrl.databaseProduct() == DatabaseProduct.SQLSERVER && configuredUrl.indexOf('&') >= 0;
    }

    // every host's instance: Oracle names one too, which Host.toString prints
    private static boolean namesPlainDatabaseAndInstance(JdbcUrl jdbcUrl, Predicate<String> spelledOut) {
        return isAbsentOrPlainName(jdbcUrl.databaseName(), spelledOut)
                && jdbcUrl.hosts().stream().allMatch(host -> isAbsentOrPlainName(host.instanceName(), spelledOut));
    }

    // ojdbc drops whitespace from a host, so "ZUSR S3Cx" reads as "ZUSRS3Cx"; localhost is the parser's default
    private static boolean spellsOutEveryHostname(JdbcUrl jdbcUrl, Predicate<String> spelledOut) {
        return jdbcUrl.hosts().stream()
                .map(Host::hostname)
                .allMatch(hostname -> DEFAULT_HOSTNAME.equals(hostname) || spelledOut.test(hostname));
    }

    // drivers rewrite names (whitespace, case, decoding), and the cut can reshape a SQLite path
    private static boolean isAbsentOrPlainName(@Nullable String name, Predicate<String> spelledOut) {
        return name == null || (PLAIN_NAME.matcher(name).matches() && spelledOut.test(name));
    }

    private static boolean spellsOut(String url, String name) {
        return url.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }
}
