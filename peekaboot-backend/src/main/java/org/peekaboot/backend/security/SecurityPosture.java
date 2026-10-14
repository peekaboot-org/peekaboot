package org.peekaboot.backend.security;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.lifecycle.LifecycleBanner;

/**
 * What Peekaboot says about the dashboard's protection at startup, and how loudly.
 *
 * <p>Its own block rather than a section of the ApplicationReady banner: that banner logs at
 * INFO, so a warning nested inside it would be invisible to anyone running at WARN, and it
 * disappears entirely while {@code peekaboot.lifecycle.enabled} is false.
 */
public final class SecurityPosture {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());

    private static final String DOCS = "https://www.peekaboot.org/docs/security/";

    private static final String ERROR_PAGE_ON_A_DEPLOYMENT =
            "peekaboot.error-page.enabled=true outside local development: any client that asks for HTML"
                    + " gets full stack traces, and no guard covers the error page.";

    public enum Level {
        WARN,
        INFO
    }

    public record Report(Level level, String text) {}

    @Nullable
    private final Report report;

    private SecurityPosture(@Nullable Report report) {
        this.report = report;
    }

    public Optional<Report> report() {
        return Optional.ofNullable(report);
    }

    /** Nothing to say: a local development or test launch, where none of this applies. */
    public static SecurityPosture quiet() {
        return new SecurityPosture(null);
    }

    /** The operator switched the guard off on a deployment launch. One reminder, not a banner. */
    public static SecurityPosture disabledOnADeployment(boolean errorPageOn) {
        String text = "Peekaboot is enabled with peekaboot.security.enabled=false outside local development."
                + " Peekaboot does not authenticate /peekaboot/**, so your application's own security"
                + " must. See " + DOCS;
        return new SecurityPosture(
                new Report(Level.WARN, errorPageOn ? text + "\n" + ERROR_PAGE_ON_A_DEPLOYMENT : text));
    }

    public static SecurityPosture armed(
            DashboardCredentials credentials,
            @Nullable Path credentialsFile,
            boolean devToolbarOn,
            boolean errorPageOnADeployment) {

        StringBuilder report = LifecycleBanner.open("Peekaboot Security");
        LifecycleBanner.line(report, " Peekaboot challenges every /peekaboot/** request with HTTP Basic.");
        LifecycleBanner.line(report, " Username: " + credentials.username());
        LifecycleBanner.line(report, passwordLine(credentials, credentialsFile));
        if (devToolbarOn) {
            LifecycleBanner.line(
                    report,
                    " The dev toolbar is on: its requests will now raise a credential dialog on ordinary pages.");
        }
        if (errorPageOnADeployment) {
            LifecycleBanner.line(report, " " + ERROR_PAGE_ON_A_DEPLOYMENT);
        }
        LifecycleBanner.line(
                report, " To use your own security instead, set peekaboot.security.enabled=false: " + DOCS);
        LifecycleBanner.close(report);

        Level level = devToolbarOn || errorPageOnADeployment ? Level.WARN : Level.INFO;
        return new SecurityPosture(new Report(level, report.toString()));
    }

    private static String passwordLine(DashboardCredentials credentials, @Nullable Path credentialsFile) {
        return switch (credentials.origin()) {
            case CONFIGURED -> " Password: taken from peekaboot.security.password";
            case LOADED ->
                " Password: unchanged since " + DATE.format(credentials.createdAt()) + ", hashed in " + credentialsFile;
            case GENERATED ->
                " Password: " + credentials.plaintext() + "  (shown once; hashed in " + credentialsFile + ")";
            case GENERATED_UNPERSISTED ->
                credentialsFile == null
                        ? " Password: " + credentials.plaintext()
                                + "  (not saved, so it will change on the next restart - set"
                                + " peekaboot.storage.enabled=true to keep it, or peekaboot.security.password to set"
                                + " your own)"
                        : " Password: " + credentials.plaintext()
                                + "  (could not be saved, so it will change on the next restart - set"
                                + " peekaboot.security.password or mount a volume for " + credentialsFile + ")";
        };
    }
}
