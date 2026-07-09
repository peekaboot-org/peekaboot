package net.osslabz.example.controller;

import java.util.List;
import java.util.Optional;
import net.osslabz.example.ExampleService;
import net.osslabz.example.entity.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class PersonApi {

    private static final Logger log = LoggerFactory.getLogger(PersonApi.class);

    private final ExampleService exampleService;


    public PersonApi(ExampleService exampleService) {

        this.exampleService = exampleService;
    }


    @GetMapping("/api/person/all")
    public List<Person> findAll() {

        return exampleService.findAll();
    }


    @GetMapping("/api/person/{id}")
    public Optional<Person> findById(@PathVariable("id") Long id) {

        return exampleService.getPerson(id);
    }
}