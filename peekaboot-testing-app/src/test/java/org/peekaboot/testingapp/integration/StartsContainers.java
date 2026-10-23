package org.peekaboot.testingapp.integration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Marks a class that starts and stops Docker containers, and runs it with no other class beside
 * it. A container's virtual interface coming up or going away changes the host's network
 * configuration, and Chromium aborts every request it has in flight on such a change with
 * {@code net::ERR_NETWORK_CHANGED}: a browser test running at that moment loses a module script,
 * a fetch or a whole page load, and then times out waiting for what never arrived.
 *
 * <p>Declare it before {@code @SpringBootTest}. JUnit registers extensions in declaration order
 * and runs their afterAll callbacks in reverse, so the class's context, closed after the class,
 * goes before the containers it is wired to stop.
 */
@Documented
@Isolated
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface StartsContainers {}
