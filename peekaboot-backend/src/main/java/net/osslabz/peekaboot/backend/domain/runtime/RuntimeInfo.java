package net.osslabz.peekaboot.backend.domain.runtime;

import java.util.List;

public record RuntimeInfo(
    OsInfo os,
    MemoryInfo memory,
    List<StorageInfo> storage,
    ProcessInfo process
) {}
