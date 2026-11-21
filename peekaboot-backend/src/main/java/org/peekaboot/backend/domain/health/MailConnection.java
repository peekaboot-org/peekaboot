package org.peekaboot.backend.domain.health;

import org.jspecify.annotations.Nullable;

/** The mail server a {@code JavaMailSender} sends to. */
public record MailConnection(String host, @Nullable Integer port) implements HealthConnection {}
