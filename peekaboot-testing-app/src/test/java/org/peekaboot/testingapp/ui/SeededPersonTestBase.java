package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.peekaboot.testingapp.entity.Person;
import org.peekaboot.testingapp.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A Playwright test that brings its own person and removes it again. The test profile has no
 * Flyway seed data, and the H2 database is shared by every IT the cached context serves.
 */
abstract class SeededPersonTestBase extends PlaywrightTestBase {

    @Autowired
    protected PersonRepository personRepository;

    protected Person person;

    @BeforeEach
    void seedAPerson() {
        Person seeded = new Person();
        seeded.setFirstName("Ada");
        seeded.setLastName("Lovelace");
        seeded.setEmail("ada@example.com");
        person = personRepository.save(seeded);
    }

    @AfterEach
    void removeThePerson() {
        personRepository.deleteById(person.getId());
    }
}
