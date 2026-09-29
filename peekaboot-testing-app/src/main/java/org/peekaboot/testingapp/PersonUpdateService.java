package org.peekaboot.testingapp;

import java.util.Optional;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonUpdateService {

    private final PersonRepository personRepository;

    public PersonUpdateService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    /** Applies the form to the stored person, flushed as one UPDATE on commit; empty when there is no such person. */
    @Transactional
    public Optional<Person> update(long id, PersonForm changes) {

        return personRepository.findById(id).map(person -> {
            person.setFirstName(changes.firstName());
            person.setLastName(changes.lastName());
            person.setEmail(changes.email());
            return person;
        });
    }
}
