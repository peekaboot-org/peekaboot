package org.peekaboot.backend.service;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CronDescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(CronDescriptionService.class);

    private final CronParser parser;

    public CronDescriptionService() {
        // SPRING53 matches @Scheduled syntax since Spring 5.3 (L, W, # etc.)
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53));
    }

    public String describe(String cronExpression, Locale locale) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }
        Locale effectiveLocale = locale != null ? locale : Locale.ENGLISH;
        try {
            Cron cron = parser.parse(cronExpression);
            return CronDescriptor.instance(effectiveLocale).describe(cron);
        } catch (IllegalArgumentException e) {
            // called on every insights refresh - keep the log noise low
            logger.debug("Failed to parse cron expression '{}': {}", cronExpression, e.getMessage());
            return null;
        }
    }
}
