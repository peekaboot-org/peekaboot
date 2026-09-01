package org.peekaboot.backend.insights.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.insights.InsightsConfigResponse;
import org.peekaboot.backend.domain.insights.LevelDataResponse;
import org.peekaboot.backend.insights.InsightsService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class InsightsControllerTest {

    private InsightsService service;
    private InsightsSsePublisher publisher;
    private InsightsController controller;

    @BeforeEach
    void setUp() {
        service = mock(InsightsService.class);
        publisher = mock(InsightsSsePublisher.class);
        controller = new InsightsController(service, publisher);
    }

    @Test
    void dataDelegatesToService() {
        LevelDataResponse response = new LevelDataResponse(1, 60_000, 60_000, 0, Map.of());
        when(service.data(1)).thenReturn(response);
        assertThat(controller.data(1)).isSameAs(response);
    }

    @Test
    void configDelegatesToService() {
        InsightsConfigResponse response = new InsightsConfigResponse(List.of(), List.of(), List.of());
        when(service.config()).thenReturn(response);
        assertThat(controller.config()).isSameAs(response);
    }

    @Test
    void streamHandsOutThePublishersEmitter() {
        SseEmitter emitter = new SseEmitter();
        when(publisher.subscribe()).thenReturn(emitter);
        assertThat(controller.stream()).isSameAs(emitter);
    }
}
