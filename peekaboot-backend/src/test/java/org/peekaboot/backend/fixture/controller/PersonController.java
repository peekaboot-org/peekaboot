package org.peekaboot.backend.fixture.controller;

import org.peekaboot.backend.fixture.service.PersonService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PersonController {

    private final PersonService personService;

    public PersonController(PersonService personService) {
        this.personService = personService;
    }

    @GetMapping("/persons")
    public String listPersons(Model model) {
        model.addAttribute("persons", personService.findAll());
        return "persons";
    }
}
