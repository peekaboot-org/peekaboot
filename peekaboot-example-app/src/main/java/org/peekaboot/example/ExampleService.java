package org.peekaboot.example;

import java.util.List;
import java.util.Optional;
import org.peekaboot.example.entity.Person;
import org.peekaboot.example.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


@Service
public class ExampleService {

    @Autowired
    PersonRepository personRepository;


    @Cacheable(cacheNames = "cache1")
    public Optional<Person> getPersonCached(long i) {

        return personRepository.findById(i);
    }


    public Optional<Person> getPerson(long i) {

        return personRepository.findById(i);
    }


    public List<Person> findAll() {

        return personRepository.findAll();
    }
}