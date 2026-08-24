package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpringInfo(String bootVersion, String frameworkVersion) {}
