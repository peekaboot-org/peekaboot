package org.peekaboot.testingapp.controller;

import org.peekaboot.testingapp.PersonForm;
import org.peekaboot.testingapp.PersonQueryService;
import org.peekaboot.testingapp.PersonUpdateService;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.profile.ProfileCall;
import org.peekaboot.testingapp.profile.ProfileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class PersonController {

    private static final Logger log = LoggerFactory.getLogger(PersonController.class);

    private final PersonQueryService personQueryService;
    private final PersonUpdateService personUpdateService;
    private final ProfileClient profileClient;

    public PersonController(
            PersonQueryService personQueryService,
            PersonUpdateService personUpdateService,
            ProfileClient profileClient) {

        this.personQueryService = personQueryService;
        this.personUpdateService = personUpdateService;
        this.profileClient = profileClient;
    }

    /**
     * The index and the persons page are the same page. {@code ?error=true} makes the handler
     * log an ERROR without failing, so one request produces a trace whose logs sit on two
     * spans: this line and the one PersonQueryService writes inside its own observed span.
     *
     * <p>The ERROR carries a real throwable - a fixture, never thrown - so this is the one
     * deterministic request in the app that gives the Logs tab's stack-trace capture and
     * folding something genuine to prove itself against. Nothing about the request's own
     * outcome changes: it still returns 200 and logs exactly one ERROR line.
     */
    @GetMapping({"/", "/persons"})
    public String persons(@RequestParam(name = "error", defaultValue = "false") boolean error, Model model) {

        model.addAttribute("persons", personQueryService.findAll());
        if (error) {
            log.error(
                    "An error occurred while trying to find all persons",
                    new IllegalStateException("person directory is unreachable"));
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

    /**
     * One person, looked up by id: the page whose trace carries a query with a bind parameter,
     * and, through {@code profile}, a call to the profile service that answers, answers slowly
     * or fails.
     */
    @GetMapping("/persons/{id}")
    public String person(
            @PathVariable("id") long id,
            @RequestParam(name = "profile", required = false) String profile,
            Model model) {

        ProfileCall call = ProfileCall.fromParameter(profile)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no profile scenario " + profile));
        Person person = existingPerson(id);
        model.addAttribute("person", person);
        model.addAttribute("profileLookup", profileClient.lookup(person.getId(), person.getEmail(), call));
        return "person";
    }

    @GetMapping("/persons/{id}/edit")
    public String editPerson(@PathVariable("id") long id, Model model) {

        model.addAttribute("person", existingPerson(id));
        return "person-edit";
    }

    /** Reached through the edit form's hidden _method field (spring.mvc.hiddenmethod.filter.enabled). */
    @PutMapping("/persons/{id}")
    public String updatePerson(@PathVariable("id") long id, PersonForm form) {

        Person updated = personUpdateService.update(id, form).orElseThrow(() -> notFound(id));
        return "redirect:/persons/" + updated.getId();
    }

    private Person existingPerson(long id) {

        return personQueryService.findById(id).orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException notFound(long id) {

        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no person " + id);
    }
}
