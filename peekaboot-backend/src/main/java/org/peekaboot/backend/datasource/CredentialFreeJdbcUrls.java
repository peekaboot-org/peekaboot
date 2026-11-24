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
 * Parses a JDBC URL, as configured or as its driver reports it, only where no host, database or instance it names
 * can hold a user or password. The health report, the dashboard's Overview DataSource card and the startup banner
 * name a target only where this class accepts its URL.
 *
 * <p>The URL is parsed as written. A URL with a user and password before the first host is described only where
 * the parser, as Connector/J does, reads them there, and they are never shown. The URL is refused where it
 * holds a character outside ASCII, an {@code @} after the scheme's {@code //} other than the one ending a user
 * and password in a first host without a {@code \}, a property that names a user or a secret, an opening brace
 * or a {@code %}. A URL the parser rejects as its driver would is refused too, as is a SQL Server URL with an
 * {@code &}, a database or instance that is no plain name or file path, and a database, instance or host the URL
 * does not spell out ({@code localhost}, the parser's default host, excepted). The health endpoint documents
 * these refusals and asks for the credentials in {@code spring.datasource.username} and
 * {@code spring.datasource.password} instead.
 */
final class CredentialFreeJdbcUrls {

    private static final MaskingEngine MASKING = new MaskingEngine();
    // the "//" (Oracle: "@//") that may open a user and password; a "//" inside an H2 or SQLite path does not
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

    static Optional<JdbcUrl> parse(String jdbcUrl) {
        return Optional.of(jdbcUrl)
                .filter(CredentialFreeJdbcUrls::isAscii)
                .filter(CredentialFreeJdbcUrls::holdsNoAtOutsideAFirstHostUserInfo)
                .filter(CredentialFreeJdbcUrls::holdsNoQuotedOrEncodedText)
                .filter(CredentialFreeJdbcUrls::namesNoCredentialProperty)
                .flatMap(CredentialFreeJdbcUrls::parseNamesItSpellsOut);
    }

    private static Optional<JdbcUrl> parseNamesItSpellsOut(String jdbcUrl) {
        Predicate<String> spelledOut = name -> spellsOut(jdbcUrl, name);
        return Optional.ofNullable(JdbcUrlParser.tryParse(jdbcUrl))
                .filter(parsed -> !isSqlServerWithAmpersand(parsed, jdbcUrl))
                .filter(parsed -> namesPlainDatabaseAndInstance(parsed, spelledOut))
                .filter(parsed -> spellsOutEveryHostname(parsed, spelledOut));
    }

    // the driver folds a long s (U+017F) to "s", so a key the credential check cannot read may still be one
    private static boolean isAscii(String url) {
        return url.chars().allMatch(c -> c < 0x80);
    }

    // Connector/J names the text after a later host's "@", or a host\instance's, as a host; it may hold a credential
    private static boolean holdsNoAtOutsideAFirstHostUserInfo(String url) {
        Matcher scheme = SCHEME.matcher(url);
        if (!scheme.lookingAt()) {
            return true;
        }
        int firstHost = scheme.end();
        int at = url.indexOf('@', firstHost);
        if (at < 0) {
            return true;
        }
        boolean onlyAt = url.indexOf('@', at + 1) < 0;
        int firstHostEnd = endOfFirstHost(url, firstHost);
        boolean insideFirstHost = at < firstHostEnd;
        boolean namesNoInstance = url.substring(firstHost, firstHostEnd).indexOf('\\') < 0;
        return onlyAt && insideFirstHost && namesNoInstance;
    }

    private static int endOfFirstHost(String url, int firstHost) {
        for (int i = firstHost; i < url.length(); i++) {
            if (FIRST_HOST_ENDS.indexOf(url.charAt(i)) >= 0) {
                return i;
            }
        }
        return url.length();
    }

    // health, the Overview card and the banner refuse quoted and encoded text, since Connector/J reads
    // "p%61ssword" as "password"
    private static boolean holdsNoQuotedOrEncodedText(String url) {
        return url.indexOf('{') < 0 && url.indexOf('%') < 0;
    }

    // health, the Overview card and the banner refuse URL credentials, which belong in spring.datasource.*
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

    // health, the Overview card and the banner refuse a SQL Server URL's "&", which can only sit inside a value
    private static boolean isSqlServerWithAmpersand(JdbcUrl parsed, String jdbcUrl) {
        return parsed.databaseProduct() == DatabaseProduct.SQLSERVER && jdbcUrl.indexOf('&') >= 0;
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

    // drivers rewrite names (whitespace, case, decoding)
    private static boolean isAbsentOrPlainName(@Nullable String name, Predicate<String> spelledOut) {
        return name == null || (PLAIN_NAME.matcher(name).matches() && spelledOut.test(name));
    }

    private static boolean spellsOut(String url, String name) {
        return url.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }
}
