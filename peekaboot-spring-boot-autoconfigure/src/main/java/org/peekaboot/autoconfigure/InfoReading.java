package org.peekaboot.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.info.EnvironmentInfoContributor;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.info.JavaInfoContributor;
import org.springframework.boot.actuate.info.OsInfoContributor;
import org.springframework.boot.actuate.info.ProcessInfoContributor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Reads {@code info} from the application's {@link InfoContributor} beans and from four more
 * the Overview needs and Spring Boot leaves off (env, java, os, process).
 *
 * <p>The four are built per read and are never beans, so the application's
 * {@code /actuator/info} never sees them.
 */
final class InfoReading {

    private InfoReading() {}

    /** {@code info.*} is read first, so an application's contributor overwrites it as Boot's build contributor does. */
    static Map<String, Object> read(
            ConfigurableEnvironment environment, List<InfoContributor> applicationContributors) {
        List<InfoContributor> contributors = new ArrayList<>();
        contributors.add(new EnvironmentInfoContributor(environment));
        contributors.addAll(applicationContributors);
        contributors.add(new JavaInfoContributor());
        contributors.add(new OsInfoContributor());
        contributors.add(new ProcessInfoContributor());
        return new InfoEndpoint(contributors).info();
    }
}
