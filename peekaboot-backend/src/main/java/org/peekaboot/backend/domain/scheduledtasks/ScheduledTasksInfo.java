package org.peekaboot.backend.domain.scheduledtasks;

import java.util.List;

public record ScheduledTasksInfo(
        List<ScheduledTaskInfo> tasks, int cronCount, int fixedDelayCount, int fixedRateCount) {}
