package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** What a deployment gets from the starter with Peekaboot switched off. */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.enabled=false")
class StarterWithPeekabootOffIT extends TestingAppITBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void queryCaptureLeavesTheDataSourcesAlone() {
        assertThat(context.getBeanNamesForType(DataSourceObservationAutoConfiguration.class))
                .isEmpty();
    }

    @Test
    void noResponseCarriesATraceId() {
        ResponseEntity<Void> response =
                api.restClient().get().uri("/persons").retrieve().toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get("Server-Timing")).isNull();
    }

    @Test
    void hibernateStatisticsStayOff() {
        assertThat(context.getBeansOfType(EntityManagerFactory.class).values())
                .isNotEmpty()
                .allSatisfy(factory -> assertThat(factory.unwrap(SessionFactory.class)
                                .getStatistics()
                                .isStatisticsEnabled())
                        .isFalse());
    }
}
