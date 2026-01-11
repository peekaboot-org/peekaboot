package net.osslabz.example.controller;

import net.osslabz.example.ExampleService;
import net.osslabz.example.repository.PersonRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PersonController {

    private final ExampleService exampleService;

    public PersonController(ExampleService exampleService) {
        this.exampleService = exampleService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("persons", exampleService.findAll());
        return "index";
    }
}