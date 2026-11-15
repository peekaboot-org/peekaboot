package org.peekaboot.backend.service;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.ActuatorReadings;
import org.peekaboot.backend.actuator.parsed.ActuatorParsedData;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.datasource.DataSourceMetadataList;
import org.peekaboot.backend.domain.actuator.ActuatorInsightsResponse;
import org.peekaboot.backend.lifecycle.ApplicationGitProperties;
import org.peekaboot.backend.mapper.actuator.ActuatorInsightsMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.beans.factory.ObjectProvider;

public final class ActuatorInsightsService {

    private final ActuatorReadings actuatorReadings;
    private final ActuatorResponseParser responseParser;
    private final ActuatorInsightsMapper insightsMapper;
    private final DataSourceMetadataList dataSourceMetadataList;
    private final @Nullable ApplicationGitProperties applicationGitProperties;

    /**
     * The mapper is built here rather than injected: it is stateless and needs only the
     * engine, so the service is the one place the masking policy enters the pipeline.
     *
     * @param applicationGitProperties null where the host turns Boot's git info contributor off, which keeps git off the Overview
     */
    public ActuatorInsightsService(
            ActuatorReadings actuatorReadings,
            ActuatorResponseParser responseParser,
            MaskingEngine maskingEngine,
            ObjectProvider<DataSourceMetadataList> dataSourceMetadataListProvider,
            @Nullable ApplicationGitProperties applicationGitProperties) {
        this.actuatorReadings = actuatorReadings;
        this.responseParser = responseParser;
        this.insightsMapper = new ActuatorInsightsMapper(maskingEngine);
        this.dataSourceMetadataList = dataSourceMetadataListProvider.getIfAvailable(() -> DataSourceMetadataList.EMPTY);
        this.applicationGitProperties = applicationGitProperties;
    }

    /**
     * {@code unmask} is the caller's already-resolved decision (from
     * {@code peekaboot.unmasking.enabled} and the request's {@code unmask} parameter,
     * combined once in PeekabootController).
     */
    public ActuatorInsightsResponse getInsights(Locale locale, boolean unmask) {
        ActuatorParsedData typed = responseParser.parse(actuatorReadings.readAll());
        return insightsMapper.map(
                typed,
                dataSourceMetadataList.entries(),
                applicationGitProperties != null ? applicationGitProperties.get() : null,
                locale,
                unmask);
    }
}
