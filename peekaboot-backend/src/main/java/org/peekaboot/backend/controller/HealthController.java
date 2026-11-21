package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.health.HealthReportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(PeekabootPaths.BASE_PATH)
public class HealthController {

    private final HealthReportService service;

    public HealthController(HealthReportService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HealthReport> health() {
        HealthReportService.HealthAnswer answer = service.report();
        return ResponseEntity.status(answer.httpStatus()).body(answer.report());
    }
}
