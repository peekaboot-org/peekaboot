package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.PersonQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class PersonController {

    private static final Logger log = LoggerFactory.getLogger(PersonController.class);

    private final PersonQueryService personQueryService;


    public PersonController(PersonQueryService personQueryService) {

        this.personQueryService = personQueryService;
    }


    @GetMapping("/")
    public String index(@RequestParam(name = "error", defaultValue = "false") boolean error, Model model) {

        model.addAttribute("persons", personQueryService.findAll());
        if (error) {
            log.error("An error occurred while trying to find all persons");
        }
        return "index";
    }
}