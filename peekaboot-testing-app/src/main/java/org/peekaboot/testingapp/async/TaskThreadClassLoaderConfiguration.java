package org.peekaboot.testingapp.async;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gives task threads the application's class loader instead of the web-app loader of whichever
 * request thread started them. Embedded Tomcat reports every live thread holding its loader when
 * it stops, and closing the context only signals the pool, so a task thread still exiting at that
 * moment shows up as a leak. Tomcat's own pools set the loader the same way.
 */
@Configuration(proxyBeanMethods = false)
class TaskThreadClassLoaderConfiguration implements BeanClassLoaderAware {

    private ClassLoader applicationClassLoader = TaskThreadClassLoaderConfiguration.class.getClassLoader();

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {

        this.applicationClassLoader = classLoader;
    }

    @Bean
    ThreadPoolTaskExecutorCustomizer applicationClassLoaderOnTaskThreads() {

        ClassLoader classLoader = applicationClassLoader;
        return executor -> executor.setThreadFactory(runnable -> {
            Thread thread = executor.newThread(runnable);
            thread.setContextClassLoader(classLoader);
            return thread;
        });
    }
}
