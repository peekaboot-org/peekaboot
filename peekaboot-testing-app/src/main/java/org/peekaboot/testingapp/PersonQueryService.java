package org.peekaboot.testingapp;

import java.util.List;
import java.util.Optional;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


@Service
public class PersonQueryService {

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