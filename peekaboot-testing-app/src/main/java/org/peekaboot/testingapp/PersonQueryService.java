package org.peekaboot.testingapp;

import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Optional;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PersonQueryService {

    private static final Logger log = LoggerFactory.getLogger(PersonQueryService.class);

    private final PersonRepository personRepository;

    public PersonQueryService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public Optional<Person> getPerson(long i) {

        return personRepository.findById(i);
    }

    /**
     * Observed so the person lookup is a span of its own rather than an anonymous gap above
     * the JDBC spans it triggers, and logs its result inside that span - which is what puts
     * a log line on a span other than the request handler's, the shape the trace overlay's
     * per-span log navigation exists to show off. {@code @Observed} is a Spring AOP aspect,
     * so this only produces a span when called from another bean, as the controllers do.
     */
    @Observed(name = "person.query.find-all", contextualName = "person.query.find-all")
    public List<Person> findAll() {

        List<Person> persons = personRepository.findAll();
        log.info("loaded {} persons", persons.size());
        return persons;
    }

    /**
     * Observed like {@link #findAll()}: the detail and edit pages show the lookup as a span of
     * its own above its one parameterised query. {@link #getPerson} stays unobserved for the
     * JSON lookup, whose flat span tree is the point of that endpoint.
     */
    @Observed(name = "person.query.find-by-id", contextualName = "person.query.find-by-id")
    public Optional<Person> findById(long id) {

        return personRepository.findById(id);
    }
}
