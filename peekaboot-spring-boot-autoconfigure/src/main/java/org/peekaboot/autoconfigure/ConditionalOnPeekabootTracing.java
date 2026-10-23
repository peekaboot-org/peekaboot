package org.peekaboot.autoconfigure;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * {@link ConditionalOnPeekabootServlet}, and tracing is on unless set to false.
 *
 * <p>Nested rather than declaring both property conditions here: Boot keeps only one
 * {@code @ConditionalOnBooleanProperty} per meta-annotation path, so a second one beside the
 * first would be dropped.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnPeekabootServlet
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
@interface ConditionalOnPeekabootTracing {}
