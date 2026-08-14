package org.peekaboot.example.controller;

import org.peekaboot.example.ExampleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class PersonController {

    private static final Logger log = LoggerFactory.getLogger(PersonController.class);

    private final ExampleService exampleService;


    public PersonController(ExampleService exampleService) {

        this.exampleService = exampleService;
    }


    @GetMapping("/")
    public String index(@RequestParam(name = "error", defaultValue = "false") boolean error, Model model) {

        model.addAttribute("persons", exampleService.findAll());
        if (error) {
            log.error("An error occurred while trying to find all persons");
        }
        return "index";
    }
}