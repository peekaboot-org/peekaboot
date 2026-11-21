package org.peekaboot.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.domain.health.MailConnection;
import org.peekaboot.backend.health.HealthConnectionSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Names the mail server each {@link JavaMailSenderImpl} sends to. */
final class MailHealthConnections implements HealthConnectionSource {

    private final Map<String, JavaMailSenderImpl> senders;

    MailHealthConnections(Map<String, JavaMailSenderImpl> senders) {
        this.senders = senders;
    }

    @Override
    public String component() {
        return "mail";
    }

    @Override
    public Map<String, HealthConnection> connections() {
        Map<String, HealthConnection> connections = new LinkedHashMap<>();
        senders.forEach((beanName, sender) -> {
            String host = sender.getHost();
            if (host != null && !host.isBlank()) {
                int port = sender.getPort();
                connections.put(beanName, new MailConnection(host, port > 0 ? port : null));
            }
        });
        return connections;
    }
}
