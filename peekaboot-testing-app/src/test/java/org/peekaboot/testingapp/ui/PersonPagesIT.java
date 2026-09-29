package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.entity.Person;

/** The person pages exist to put a parameterised lookup and an UPDATE behind a click. */
class PersonPagesIT extends SeededPersonTestBase {

    @Test
    void theListLinksEachPersonToTheirDetailPage() {
        page.navigate(baseUrl + "/persons");

        page.click(row() + " a:text-is('View')");

        page.waitForURL(baseUrl + "/persons/" + person.getId());
        assertThat(page.textContent("#person-email")).isEqualTo("ada@example.com");
    }

    @Test
    void theListLinksEachPersonToTheirEditPage() {
        page.navigate(baseUrl + "/persons");

        page.click(row() + " a:text-is('Edit')");

        page.waitForURL(baseUrl + "/persons/" + person.getId() + "/edit");
        assertThat(page.inputValue("#email")).isEqualTo("ada@example.com");
    }

    /** The form posts _method=put; without the hidden-method filter no mapping takes the POST and the save answers 405. */
    @Test
    void savingTheEditFormUpdatesThePersonAndShowsTheirDetailPage() {
        page.navigate(baseUrl + "/persons/" + person.getId() + "/edit");

        page.fill("#email", "ada@analytical.example");
        page.click("button[type=submit]");

        page.waitForURL(baseUrl + "/persons/" + person.getId());
        assertThat(page.textContent("#person-email")).isEqualTo("ada@analytical.example");
        assertThat(personRepository.findById(person.getId()))
                .get()
                .extracting(Person::getEmail)
                .isEqualTo("ada@analytical.example");
    }

    @Test
    void anUnknownPersonIsNotFound() {
        String unknown = baseUrl + "/persons/" + Long.MAX_VALUE;

        assertThat(page.navigate(unknown).status()).isEqualTo(404);
        assertThat(page.navigate(unknown + "/edit").status()).isEqualTo(404);
        APIResponse save = page.request()
                .post(
                        unknown,
                        RequestOptions.create()
                                .setForm(FormData.create()
                                        .set("_method", "put")
                                        .set("firstName", "x")
                                        .set("lastName", "y")
                                        .set("email", "z@example.com")));
        assertThat(save.status()).isEqualTo(404);
    }

    private String row() {
        return "tr[data-person-id='" + person.getId() + "']";
    }
}
