package net.osslabz.peekaboot.backend.service;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class CronDescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(CronDescriptionService.class);

    private final CronParser parser;

    public CronDescriptionService() {
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING));
    }

    public String describe(String cronExpression, Locale locale) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }
        Locale effectiveLocale = locale != null ? locale : Locale.ENGLISH;
        try {
            Cron cron = parser.parse(cronExpression);
            return CronDescriptor.instance(effectiveLocale).describe(cron);
        } catch (Exception e) {
            logger.warn("Failed to parse cron expression: {}", cronExpression, e);
            return null;
        }
    }
}
