package org.peekaboot.backend.domain.liquibase;

import java.time.Instant;

public record ChangeSetInfo(
        Integer orderExecuted,
        String id,
        String author,
        String changeLog,
        String description,
        Instant dateExecuted,
        ChangeSetExecType execType) {}
