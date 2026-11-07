package org.peekaboot.backend.domain.api;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;

/** The OpenAPI document as {@code GET /peekaboot/api/openapi.json} serves it, written as the JSON it holds. */
public record OpenApiDocument(@JsonValue JsonNode content) {}
