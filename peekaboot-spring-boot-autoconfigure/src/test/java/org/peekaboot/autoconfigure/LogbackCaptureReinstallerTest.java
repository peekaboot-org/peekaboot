package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.StandardEnvironment;

class LogbackCaptureReinstallerTest {

    /** spring.factories registers the listener for every application, so it must run where Logback is absent. */
    @Test
    void resolvesNoLogbackTypeWhereLogbackIsAbsent() throws ReflectiveOperationException {
        Class<?> reinstaller = new LogbackHidingClassLoader(getClass().getClassLoader())
                .loadClass(LogbackCaptureReinstaller.class.getName());
        Constructor<?> constructor = reinstaller.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object listener = constructor.newInstance();
        Method onEvent = reinstaller.getMethod("onApplicationEvent", ApplicationEnvironmentPreparedEvent.class);
        onEvent.setAccessible(true);
        ApplicationEnvironmentPreparedEvent event = new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], new StandardEnvironment());

        assertThat(reinstaller.getClassLoader()).isInstanceOf(LogbackHidingClassLoader.class);
        assertThatCode(() -> onEvent.invoke(listener, event)).doesNotThrowAnyException();
    }

    /** Defines this module's classes itself, so their Logback references resolve against a class path without Logback. */
    private static final class LogbackHidingClassLoader extends ClassLoader {

        private static final String OWN_PACKAGE = LogbackCaptureReinstaller.class.getPackageName() + ".";

        LogbackHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("ch.qos.logback.")) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.startsWith(OWN_PACKAGE)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineOwn(name);
            }
        }

        private Class<?> defineOwn(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
