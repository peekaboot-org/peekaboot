package org.peekaboot.backend.domain.loggers;

import java.util.List;

public record LoggerGroup(String packageName, List<LoggerInfo> loggers) {}
