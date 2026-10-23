package org.peekaboot.testingapp.ui;

import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The sample app behind a {@code server.servlet.context-path}, in a context of its own so every
 * other class keeps the root-mounted app. Every subclass shares that one cached context.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=" + ContextPathTestBase.CONTEXT_PATH)
abstract class ContextPathTestBase extends PlaywrightTestBase {

    static final String CONTEXT_PATH = "/app";

    @Override
    protected String contextPath() {
        return CONTEXT_PATH;
    }
}
