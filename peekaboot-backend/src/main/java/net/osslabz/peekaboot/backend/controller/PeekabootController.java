package net.osslabz.peekaboot.backend.controller;

import java.util.Map;
import net.osslabz.peekaboot.backend.service.PeekabootService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/peekaboot")
public class PeekabootController {

    private final PeekabootService peekabootService;


    public PeekabootController(PeekabootService peekabootService) {

        this.peekabootService = peekabootService;
    }


    @GetMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getData() {

        return peekabootService.getData();
    }


    @GetMapping(value = "/api/v2", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getDataGeneric() {

        return peekabootService.getDataGeneric();
    }
}