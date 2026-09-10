package org.peekaboot.testingapp.controller;

import java.util.List;
import org.peekaboot.testingapp.PersonQueryService;
import org.peekaboot.testingapp.entity.Person;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PersonApi {

    private final PersonQueryService personQueryService;

    public PersonApi(PersonQueryService personQueryService) {

        this.personQueryService = personQueryService;
    }

    @GetMapping("/api/person/all")
    public List<Person> findAll() {

        return personQueryService.findAll();
    }

    /** {@code ResponseEntity.of}, not the bare {@code Optional}: that answers 200 with a null body. */
    @GetMapping("/api/person/{id}")
    public ResponseEntity<Person> findById(@PathVariable("id") Long id) {

        return ResponseEntity.of(personQueryService.getPerson(id));
    }
}
