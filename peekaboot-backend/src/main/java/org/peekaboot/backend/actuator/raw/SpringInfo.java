package org.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpringInfo(
    String bootVersion,
    String frameworkVersion
) {
}
