package org.peekaboot.backend.domain.server;

import java.nio.charset.Charset;
import java.time.Instant;
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
    public static ServerInfo current(Locale requestLocale) {
        Locale effectiveLocale = requestLocale != null ? requestLocale : Locale.ENGLISH;

        ZoneId zone = ZoneId.systemDefault();
        String offset = zone.getRules().getOffset(Instant.now()).toString();
        String display = zone.getDisplayName(TextStyle.FULL, effectiveLocale);

        ZonedDateTime now = ZonedDateTime.now(zone);
        String currentTime = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

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
