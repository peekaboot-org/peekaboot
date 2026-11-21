package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.health.MailConnection;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailHealthConnectionsTest {

    @Test
    void describesEachSenderByBeanName() {
        Map<String, JavaMailSenderImpl> senders = new LinkedHashMap<>();
        senders.put("mailSender", sender("smtp.example.org", 587));
        senders.put("bulkSender", sender("bulk.example.org", 25));

        assertThat(new MailHealthConnections(senders).connections())
                .containsExactly(
                        Map.entry("mailSender", new MailConnection("smtp.example.org", 587)),
                        Map.entry("bulkSender", new MailConnection("bulk.example.org", 25)));
    }

    @Test
    void aSenderWithoutAPortHasANullPort() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("smtp.example.org");

        assertThat(new MailHealthConnections(Map.of("mailSender", sender)).connections())
                .containsExactly(Map.entry("mailSender", new MailConnection("smtp.example.org", null)));
    }

    @Test
    void aSenderWithoutAHostIsLeftOut() {
        assertThat(new MailHealthConnections(Map.of("mailSender", new JavaMailSenderImpl())).connections())
                .isEmpty();
    }

    private static JavaMailSenderImpl sender(String host, int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        return sender;
    }
}
