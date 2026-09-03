package org.peekaboot.backend.mapper.actuator;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Puts a cron expression into words for the Scheduled Tasks tab ("every hour"), in the reader's locale. */
final class CronDescriber {

    private static final Logger log = LoggerFactory.getLogger(CronDescriber.class);

    private final CronParser parser;

    CronDescriber() {
        // SPRING53 matches @Scheduled syntax since Spring 5.3 (L, W, # etc.)
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53));
    }

    /** Null for a blank or unparseable expression; the tab then shows the raw expression alone. */
    String describe(String cronExpression, Locale locale) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }
        Locale effectiveLocale = locale != null ? locale : Locale.ENGLISH;
        try {
            Cron cron = parser.parse(cronExpression);
            return CronDescriptor.instance(effectiveLocale).describe(cron);
        } catch (IllegalArgumentException e) {
            // called on every insights refresh - keep the log noise low
            log.debug("Failed to parse cron expression '{}': {}", cronExpression, e.getMessage());
            return null;
        }
    }
}
