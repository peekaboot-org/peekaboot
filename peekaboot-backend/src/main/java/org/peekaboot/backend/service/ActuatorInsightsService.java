package org.peekaboot.backend.service;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.ActuatorReadings;
import org.peekaboot.backend.actuator.parsed.ActuatorParsedData;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.datasource.DataSourceMetadataList;
import org.peekaboot.backend.domain.actuator.ActuatorInsightsResponse;
import org.peekaboot.backend.lifecycle.ApplicationGitProperties;
import org.peekaboot.backend.mapper.actuator.ActuatorInsightsMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.problem.RequestParameters;
import org.springframework.beans.factory.ObjectProvider;

public final class ActuatorInsightsService {

    private final ActuatorReadings actuatorReadings;
    private final ActuatorResponseParser responseParser;
    private final ActuatorInsightsMapper insightsMapper;
    private final DataSourceMetadataList dataSourceMetadataList;
    private final @Nullable ApplicationGitProperties applicationGitProperties;
    private final PeekabootProperties properties;

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
            @Nullable ApplicationGitProperties applicationGitProperties,
            PeekabootProperties properties) {
        this.actuatorReadings = actuatorReadings;
        this.responseParser = responseParser;
        this.insightsMapper = new ActuatorInsightsMapper(maskingEngine);
        this.dataSourceMetadataList = dataSourceMetadataListProvider.getIfAvailable(() -> DataSourceMetadataList.EMPTY);
        this.applicationGitProperties = applicationGitProperties;
        this.properties = properties;
    }

    /**
     * The insights as one request asks for them: in its {@code locale}, English where it
     * names none, and unmasked only where {@code peekaboot.unmasking.enabled} and the
     * request both say so. This is the single place the two opt-ins are combined, so the
     * request alone can never be a bypass.
     */
    public ActuatorInsightsResponse insightsFor(@Nullable String locale, boolean unmaskRequested) {
        Locale requested = RequestParameters.locale("locale", locale);
        return getInsights(
                requested != null ? requested : Locale.ENGLISH,
                properties.getUnmasking().isEnabled() && unmaskRequested);
    }

    /** {@code unmask} is the caller's already-resolved decision, see {@link #insightsFor}. */
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
