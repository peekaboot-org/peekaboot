package net.osslabz.example.controller;

import net.osslabz.example.repository.PersonRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PersonController {

    private final PersonRepository personRepository;

    public PersonController(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("persons", personRepository.findAll());
        return "index";
    }
}
