package org.peekaboot.backend.domain.server;

import java.nio.charset.Charset;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

public record ServerInfo(
        String timezone,
        String timezoneOffset,
        String timezoneDisplay,
        String currentTime,
        String locale,
        String localeDisplay,
        String fileEncoding,
        String lineSeparator,
        int availableProcessors) {

    /** The server's own zone is the fact being reported, so the default zone is the right one here. */
    public static ServerInfo current(Locale requestLocale) {
        return current(requestLocale, Clock.system(ZoneId.systemDefault()));
    }

    /** The clock supplies the zone and the instant, so a test can pin an offset without waiting for a season. */
    public static ServerInfo current(Locale requestLocale, Clock clock) {
        Locale effectiveLocale = requestLocale != null ? requestLocale : Locale.ENGLISH;

        ZoneId zone = clock.getZone();
        String offset = zone.getRules().getOffset(clock.instant()).toString();
        String display = zone.getDisplayName(TextStyle.FULL, effectiveLocale);

        String currentTime = ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Locale defaultLocale = Locale.getDefault();
        String localeTag = defaultLocale.toLanguageTag();
        String localeDisplayName = defaultLocale.getDisplayName(effectiveLocale);

        String encoding = Charset.defaultCharset().name();
        String lineSep = System.lineSeparator().replace("\r", "\\r").replace("\n", "\\n");
        int processors = Runtime.getRuntime().availableProcessors();

        return new ServerInfo(
                zone.getId(),
                offset,
                display,
                currentTime,
                localeTag,
                localeDisplayName,
                encoding,
                lineSep,
                processors);
    }
}
