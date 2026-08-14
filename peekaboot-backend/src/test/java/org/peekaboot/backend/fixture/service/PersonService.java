package org.peekaboot.backend.fixture.service;

import java.util.List;

import org.peekaboot.backend.fixture.entity.Person;
import org.peekaboot.backend.fixture.repository.PersonRepository;
import org.springframework.stereotype.Service;

@Service
public class PersonService {

    private final PersonRepository personRepository;

    public PersonService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public List<Person> findAll() {
        return personRepository.findAll();
    }

    public Person save(Person person) {
        return personRepository.save(person);
    }
}
