package net.osslabz.peekaboot.backend.domain.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

public record ServerInfo(
    String timezone,
    String timezoneOffset,
    String timezoneDisplay
) {
    public static ServerInfo current() {
        return current(Locale.ENGLISH);
    }

    public static ServerInfo current(Locale locale) {
        ZoneId zone = ZoneId.systemDefault();
        String offset = zone.getRules().getOffset(Instant.now()).toString();
        Locale effectiveLocale = locale != null ? locale : Locale.ENGLISH;
        String display = zone.getDisplayName(TextStyle.FULL, effectiveLocale);
        return new ServerInfo(zone.getId(), offset, display);
    }
}
