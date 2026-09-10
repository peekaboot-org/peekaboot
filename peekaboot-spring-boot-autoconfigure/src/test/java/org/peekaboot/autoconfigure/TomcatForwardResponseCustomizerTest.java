package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.core.StandardContext;
import org.junit.jupiter.api.Test;

class TomcatForwardResponseCustomizerTest {

    @Test
    void closesWrappedResponsesAfterAForwardInsteadOfSuspendingThem() {
        StandardContext context = new StandardContext();
        assertThat(context.getSuspendWrappedResponseAfterForward())
                .as("Tomcat 11 default")
                .isTrue();

        new TomcatForwardResponseCustomizer().customize(context);

        assertThat(context.getSuspendWrappedResponseAfterForward()).isFalse();
    }
}
