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

    /**
     * The index and the persons page are the same page. {@code ?error=true} makes the handler
     * log an ERROR without failing, so one request produces a trace whose logs sit on two
     * spans: this line and the one PersonQueryService writes inside its own observed span.
     */
    @GetMapping({"/", "/persons"})
    public String persons(@RequestParam(name = "error", defaultValue = "false") boolean error, Model model) {

        model.addAttribute("persons", personQueryService.findAll());
        if (error) {
            log.error("An error occurred while trying to find all persons");
        }
        return "persons";
    }

    /**
     * The person list's former path, kept as a forward. Exists so a trace with a nested
     * dispatch in it - a second DispatcherServlet dispatch running inside the first one's
     * view rendering - is something the trace view can be pointed at.
     */
    @GetMapping("/people")
    public String people() {

        return "forward:/persons";
    }
}
