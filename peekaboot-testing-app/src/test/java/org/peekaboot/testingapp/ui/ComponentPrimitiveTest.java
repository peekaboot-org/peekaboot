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

        // Pin a components.css-authored fact: the header's own border-radius changes
        // with its aria-expanded state (full radius when collapsed, top-only when
        // expanded) — asserting only the [hidden] list leaves the header CSS unpinned.
        String collapsedRadius = (String) page.evalOnSelector("#group-header",
                "el => getComputedStyle(el).borderRadius");
        String expandedRadius = (String) page.evalOnSelector("#group-header-expanded",
                "el => getComputedStyle(el).borderRadius");
        assertThat(collapsedRadius).isNotEqualTo(expandedRadius);
    }

    @Test
    void groupHeaderIsAButtonAndKeyboardReachable() {
        openFixture();

        String tag = (String) page.evalOnSelector("#group-header", "el => el.tagName");
        assertThat(tag).isEqualTo("BUTTON");

        page.focus("#group-header");
        String focused = (String) page.evaluate("() => document.activeElement.id");
        assertThat(focused).isEqualTo("group-header");

        // Pin the header's own styling, not just its semantics: a plain unstyled
        // <button> would also pass the assertions above.
        String background = (String) page.evalOnSelector("#group-header",
                "el => getComputedStyle(el).backgroundColor");
        assertThat(background).isNotEqualTo("rgba(0, 0, 0, 0)");

        String headerFontSize = (String) page.evalOnSelector("#group-header",
                "el => getComputedStyle(el).fontSize");
        String bodyFontSize = (String) page.evalOnSelector("body",
                "el => getComputedStyle(el).fontSize");
        assertThat(headerFontSize).isEqualTo(bodyFontSize);
    }

    @Test
    void groupHeaderShowsAFocusVisibleOutline() {
        openFixture();

        page.focus("#group-header");
        String outlineStyle = (String) page.evalOnSelector("#group-header",
                "el => getComputedStyle(el).outlineStyle");
        String outlineWidth = (String) page.evalOnSelector("#group-header",
                "el => getComputedStyle(el).outlineWidth");

        // Chromium already draws its own default focus ring (1px "auto" outline) on a
        // plain <button> with no CSS at all, so "not none" alone can't fail on this
        // deliverable. .pk-group__header:focus-visible authors a specific 2px solid
        // outline, which is what actually needs pinning.
        assertThat(outlineStyle).isEqualTo("solid");
        assertThat(outlineWidth).isEqualTo("2px");

        // page.focus() alone happens to satisfy Chromium's :focus-visible heuristic on
        // this build, same as a real Tab keypress, but that's a heuristic, not a spec
        // guarantee. Pin the other side too: a real mouse click on a fresh page must
        // NOT produce the outline above, or this test could stop discriminating without
        // ever failing.
        openFixture();
        page.click("#group-header");
        String clickedOutlineStyle = (String) page.evalOnSelector("#group-header",
                "el => getComputedStyle(el).outlineStyle");
        assertThat(clickedOutlineStyle).isEqualTo("none");
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

        Object width = page.evalOnSelector("#meter-fill-danger",
                "el => el.getBoundingClientRect().width / el.parentElement.getBoundingClientRect().width");
        assertThat((Double) width).isCloseTo(0.95, org.assertj.core.data.Offset.offset(0.02));

        String baseColor = (String) page.evalOnSelector("#meter-fill-base",
                "el => getComputedStyle(el).backgroundColor");
        String dangerColor = (String) page.evalOnSelector("#meter-fill-danger",
                "el => getComputedStyle(el).backgroundColor");
        assertThat(dangerColor).isNotEqualTo(baseColor);

        String overflow = (String) page.evalOnSelector("#meter-danger",
                "el => getComputedStyle(el).overflow");
        assertThat(overflow).isEqualTo("hidden");
    }

    @Test
    void emptyStateIsCenteredAndMuted() {
        openFixture();

        String textAlign = (String) page.evalOnSelector("#empty",
                "el => getComputedStyle(el).textAlign");
        String fontStyle = (String) page.evalOnSelector("#empty",
                "el => getComputedStyle(el).fontStyle");

        assertThat(textAlign).isEqualTo("center");
        assertThat(fontStyle).isEqualTo("italic");
    }

    @Test
    void buttonIsStyledAndClickable() {
        openFixture();

        String display = (String) page.evalOnSelector("#btn",
                "el => getComputedStyle(el).display");
        String cursor = (String) page.evalOnSelector("#btn",
                "el => getComputedStyle(el).cursor");

        assertThat(display).isEqualTo("inline-flex");
        assertThat(cursor).isEqualTo("pointer");
    }

    @Test
    void selectedTabIsVisuallyDistinctFromUnselected() {
        openFixture();

        String selectedColor = (String) page.evalOnSelector("#tab-selected",
                "el => getComputedStyle(el).color");
        String unselectedColor = (String) page.evalOnSelector("#tab-unselected",
                "el => getComputedStyle(el).color");
        String selectedBorder = (String) page.evalOnSelector("#tab-selected",
                "el => getComputedStyle(el).borderBottomColor");
        String unselectedBorder = (String) page.evalOnSelector("#tab-unselected",
                "el => getComputedStyle(el).borderBottomColor");

        assertThat(selectedColor).isNotEqualTo(unselectedColor);
        assertThat(selectedBorder).isNotEqualTo(unselectedBorder);
    }
}
