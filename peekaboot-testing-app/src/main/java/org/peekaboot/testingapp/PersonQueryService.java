package org.peekaboot.testingapp;

import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Optional;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class PersonQueryService {

    private static final Logger log = LoggerFactory.getLogger(PersonQueryService.class);

    @Autowired
    PersonRepository personRepository;

    @Cacheable(cacheNames = "cache1")
    public Optional<Person> getPersonCached(long i) {

        return personRepository.findById(i);
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
}
