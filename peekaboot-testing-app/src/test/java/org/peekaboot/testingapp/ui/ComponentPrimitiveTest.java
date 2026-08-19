package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentPrimitiveTest extends PlaywrightTestBase {

    private void openFixture() {
        page.navigate(baseUrl + "/pk-component-fixture.html");
        page.waitForSelector("#group");
    }

    @Test
    void badgeVariantsUseTheirSemanticColours() {
        openFixture();

        String ok = (String) page.evalOnSelector("#badge-ok",
                "el => getComputedStyle(el).backgroundColor");
        String error = (String) page.evalOnSelector("#badge-error",
                "el => getComputedStyle(el).backgroundColor");

        assertThat(ok).isNotEqualTo(error);
        assertThat(ok).isNotEqualTo("rgba(0, 0, 0, 0)");
    }

    @Test
    void collapsedGroupListIsNotVisible() {
        openFixture();

        assertThat(page.isVisible("#group-list")).isFalse();
    }

    @Test
    void groupHeaderIsAButtonAndKeyboardReachable() {
        openFixture();

        String tag = (String) page.evalOnSelector("#group-header", "el => el.tagName");
        assertThat(tag).isEqualTo("BUTTON");

        page.focus("#group-header");
        String focused = (String) page.evaluate("() => document.activeElement.id");
        assertThat(focused).isEqualTo("group-header");
    }

    @Test
    void sensitiveValueIsVisuallyDistinct() {
        openFixture();

        String sensitive = (String) page.evalOnSelector(".pk-kv__value--sensitive",
                "el => getComputedStyle(el).fontStyle");
        assertThat(sensitive).isEqualTo("italic");
    }

    @Test
    void meterFillRespectsItsWidth() {
        openFixture();

        Object width = page.evalOnSelector(".pk-meter__fill",
                "el => el.getBoundingClientRect().width / el.parentElement.getBoundingClientRect().width");
        assertThat((Double) width).isCloseTo(0.95, org.assertj.core.data.Offset.offset(0.02));
    }
}
