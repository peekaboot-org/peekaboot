package net.osslabz.peekaboot.backend.service;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class CronDescriptionService {

    private final CronParser parser;

    public CronDescriptionService() {
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING));
    }

    public String describe(String cronExpression, Locale locale) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }
        try {
            Cron cron = parser.parse(cronExpression);
            return CronDescriptor.instance(locale).describe(cron);
        } catch (Exception e) {
            return null;
        }
    }
}
