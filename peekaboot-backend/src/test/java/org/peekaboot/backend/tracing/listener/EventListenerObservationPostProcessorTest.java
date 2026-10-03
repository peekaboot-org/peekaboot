package org.peekaboot.backend.tracing.listener;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.example.listener.Listeners;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.autoconfigure.peekabootfixture.AutoConfigurationListener;
import org.peekaboot.backend.domain.trace.EventListenerMarker;
import org.peekaboot.backend.testsupport.RecordingObservations;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.peekabootfixture.FrameworkListener;

class EventListenerObservationPostProcessorTest {

    private final RecordingObservations observations = new RecordingObservations();
    private EventListenerObservationPostProcessor postProcessor;

    @BeforeEach
    void setUp() {
        postProcessor = new EventListenerObservationPostProcessor(observations::registry);
        postProcessor.setBeanFactory(new DefaultListableBeanFactory());
    }

    @Test
    void proxiesABeanWithAnEventListenerMethodAndObservesItsCalls() throws Exception {
        Object bean = postProcess(new Listeners.Plain());

        assertThat(AopUtils.isCglibProxy(bean)).isTrue();
        observations.insideParentObservation(
                () -> ((Listeners.Plain) bean).onOrderPlaced(new Listeners.OrderPlaced("PK-1")));
        assertThat(listenerSpanNames()).containsExactly("Plain#onOrderPlaced");
    }

    @Test
    void proxiesATransactionalEventListener() {
        assertThat(AopUtils.isAopProxy(postProcess(new Listeners.AfterCommit())))
                .isTrue();
    }

    @Test
    void leavesABeanWithoutListenersUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new Listeners.NoListener()))).isFalse();
    }

    /** The application already raises a span for an @Observed method; a second one would only duplicate it. */
    @Test
    void doesNotObserveAListenerMethodTheApplicationAlreadyObserves() throws Exception {
        Listeners.PartlyObserved bean = (Listeners.PartlyObserved) postProcess(new Listeners.PartlyObserved());

        observations.insideParentObservation(() -> {
            bean.onOrderPlaced(new Listeners.OrderPlaced("PK-1"));
            bean.onRejectedOrder(new Listeners.RejectedOrder("out of stock"));
            return null;
        });

        assertThat(listenerSpanNames()).containsExactly("PartlyObserved#onRejectedOrder");
    }

    @Test
    void leavesAClassTheApplicationObservesAsAWholeUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new Listeners.ObservedClass())))
                .isFalse();
    }

    /** The trace store's own listeners would otherwise raise a span for every log line they store. */
    @Test
    void leavesPeekabootsOwnListenersUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new PeekabootOwnListener()))).isFalse();
    }

    @Test
    void leavesTheAutoConfigurationModulesOwnListenersUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new AutoConfigurationListener())))
                .isFalse();
    }

    @Test
    void leavesSpringsOwnListenersUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new FrameworkListener()))).isFalse();
    }

    /** A static listener method is called without the bean, so a proxy would never see the call. */
    @Test
    void leavesAClassWhoseOnlyListenerMethodIsStaticUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new Listeners.StaticListener())))
                .isFalse();
    }

    /** CGLIB cannot subclass a final class, and Kotlin classes are final unless opened. */
    @Test
    void leavesAFinalClassUnproxiedAndSaysWhy() {
        assertLeftUnproxied(new Listeners.FinalClass(), "final");
    }

    @Test
    void leavesASealedClassUnproxiedAndSaysWhy() {
        assertLeftUnproxied(new Listeners.SealedClass(), "sealed");
    }

    @Test
    void leavesAnEnumWithAConstantBodyUnproxiedAndSaysWhy() {
        assertLeftUnproxied(Listeners.Channel.EMAIL, "sealed");
    }

    @Test
    void leavesAClassWithOnlyPrivateConstructorsUnproxiedAndSaysWhy() {
        assertLeftUnproxied(Listeners.PrivateConstructor.create(), "constructor");
    }

    /** A JDK proxy would hide every listener method that no interface declares, and Spring would refuse to start. */
    @Test
    void proxiesAListenerThatImplementsAnInterfaceThroughItsClass() {
        assertThat(AopUtils.isCglibProxy(postProcess(new Listeners.NotifyingListener())))
                .isTrue();
    }

    /** Spring cannot add an advisor to a frozen proxy, so it proxies the proxy. */
    @Test
    void observesAListenerBehindAFrozenProxyByProxyingTheProxy() throws Exception {
        ProxyFactory frozenFactory = new ProxyFactory(new Listeners.Plain());
        frozenFactory.setProxyTargetClass(true);
        frozenFactory.setFrozen(true);
        Listeners.Plain frozen = (Listeners.Plain) frozenFactory.getProxy();

        Listeners.Plain bean = (Listeners.Plain) postProcess(frozen);

        assertThat(bean).isNotSameAs(frozen);
        observations.insideParentObservation(() -> bean.onOrderPlaced(new Listeners.OrderPlaced("PK-1")));
        assertThat(listenerSpanNames()).containsExactly("Plain#onOrderPlaced");
    }

    /** Records and other final beans without listeners are no reason to log. */
    @Test
    void saysNothingAboutAFinalClassWithoutListeners() {
        try (LogCapture logs = LogCapture.attach(EventListenerPointcut.class, Level.DEBUG)) {
            assertThat(AopUtils.isAopProxy(postProcess(new Listeners.OrderPlaced("PK-1"))))
                    .isFalse();

            assertThat(logs.appender().list).isEmpty();
        }
    }

    /** Spring refuses to start when a private listener method sits behind a proxy. */
    @Test
    void leavesAClassWithAPrivateListenerMethodUnproxied() {
        assertThat(AopUtils.isAopProxy(postProcess(new Listeners.PrivateMethod())))
                .isFalse();
    }

    /** A final method runs on the proxy instance itself, whose fields were never set. */
    @Test
    void leavesAClassWithAFinalListenerMethodUnproxiedAndSaysWhy() {
        assertLeftUnproxied(new Listeners.FinalMethod(), "final method onOrderPlaced()");
    }

    /** A final method runs on the proxy instance itself, whose fields were never set. */
    @Test
    void leavesAClassWithAFinalMethodUnproxiedAndSaysWhy() {
        Listeners.FinalAccessor bean = new Listeners.FinalAccessor("PK-1");

        assertLeftUnproxied(bean, "final method reference()");
        assertThat(((Listeners.FinalAccessor) postProcess(bean)).reference()).isEqualTo("PK-1");
    }

    @Test
    void leavesAClassInheritingAFinalMethodUnproxied() {
        Listeners.FinalAccessor bean = new Listeners.InheritedFinalAccessor("PK-1");

        assertThat(((Listeners.FinalAccessor) postProcess(bean)).reference()).isEqualTo("PK-1");
    }

    /** Neither runs on the proxy instance: a private method is called on the target, a static one on no instance. */
    @Test
    void proxiesAClassWhoseOnlyFinalMethodsArePrivateOrStatic() {
        assertThat(AopUtils.isCglibProxy(postProcess(new Listeners.PrivateAndStaticFinalMethods())))
                .isTrue();
    }

    /** CGLIB cannot subclass the bean's class, so proxying it would fail the bean's creation. */
    private void assertLeftUnproxied(Object bean, String reason) {
        Class<?> beanClass = bean.getClass();
        try (LogCapture logs = LogCapture.attach(EventListenerPointcut.class, Level.DEBUG)) {
            assertThat(postProcessor.determineBeanType(beanClass, "listener")).isEqualTo(beanClass);
            assertThat(AopUtils.isAopProxy(postProcess(bean))).isFalse();

            assertThat(logs.appender().list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .contains(beanClass.getName())
                        .contains(reason);
            });
        }
    }

    private Object postProcess(Object bean) {
        return postProcessor.postProcessAfterInitialization(bean, "listener");
    }

    private List<String> listenerSpanNames() {
        return observations.startedNamed(EventListenerMarker.OBSERVATION_NAME).stream()
                .map(Observation.Context::getContextualName)
                .toList();
    }

    /** Sits in Peekaboot's backend package, like the trace store's own listener. */
    public static class PeekabootOwnListener {

        @EventListener
        public void onOrderPlaced(Listeners.OrderPlaced event) {}
    }
}
