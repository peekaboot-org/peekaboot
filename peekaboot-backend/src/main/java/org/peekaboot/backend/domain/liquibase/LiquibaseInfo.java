package org.peekaboot.backend.domain.liquibase;

import java.util.List;

public record LiquibaseInfo(List<ChangeSetInfo> changeSets) {}
