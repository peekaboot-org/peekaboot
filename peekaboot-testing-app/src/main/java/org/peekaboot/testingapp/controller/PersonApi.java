package org.peekaboot.testingapp.controller;

import java.util.List;
import java.util.Optional;
import org.peekaboot.testingapp.PersonQueryService;
import org.peekaboot.testingapp.entity.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class PersonApi {

    private static final Logger log = LoggerFactory.getLogger(PersonApi.class);

    private final PersonQueryService personQueryService;


    public PersonApi(PersonQueryService personQueryService) {

        this.personQueryService = personQueryService;
    }


    @GetMapping("/api/person/all")
    public List<Person> findAll() {

        return personQueryService.findAll();
    }


    @GetMapping("/api/person/{id}")
    public Optional<Person> findById(@PathVariable("id") Long id) {

        return personQueryService.getPerson(id);
    }
}