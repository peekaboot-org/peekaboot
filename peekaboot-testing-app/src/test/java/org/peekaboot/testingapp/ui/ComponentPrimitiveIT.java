package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class ComponentPrimitiveIT extends PlaywrightTestBase {

    private void openFixture() {
        page.navigate(baseUrl + "/pk-component-fixture.html");
        page.waitForSelector("#group");
    }

    @Test
    void badgeVariantsUseTheirSemanticColours() {
        openFixture();

        String ok = (String) page.evalOnSelector("#badge-ok", "el => getComputedStyle(el).backgroundColor");
        String error = (String) page.evalOnSelector("#badge-error", "el => getComputedStyle(el).backgroundColor");

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
    void groupHeaderShowsAFocusVisibleOutline() {
        openFixture();

        page.focus("#group-header");
        String outlineStyle = (String) page.evalOnSelector("#group-header", "el => getComputedStyle(el).outlineStyle");
        String outlineWidth = (String) page.evalOnSelector("#group-header", "el => getComputedStyle(el).outlineWidth");

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
        String clickedOutlineStyle =
                (String) page.evalOnSelector("#group-header", "el => getComputedStyle(el).outlineStyle");
        assertThat(clickedOutlineStyle).isEqualTo("none");
    }

    @Test
    void meterFillRespectsItsWidth() {
        openFixture();

        Object width = page.evalOnSelector(
                "#meter-fill-danger",
                "el => el.getBoundingClientRect().width / el.parentElement.getBoundingClientRect().width");
        assertThat((Double) width).isCloseTo(0.95, Offset.offset(0.02));

        String baseColor =
                (String) page.evalOnSelector("#meter-fill-base", "el => getComputedStyle(el).backgroundColor");
        String dangerColor =
                (String) page.evalOnSelector("#meter-fill-danger", "el => getComputedStyle(el).backgroundColor");
        assertThat(dangerColor).isNotEqualTo(baseColor);

        String overflow = (String) page.evalOnSelector("#meter-danger", "el => getComputedStyle(el).overflow");
        assertThat(overflow).isEqualTo("hidden");
    }

    @Test
    void selectedTabIsVisuallyDistinctFromUnselected() {
        openFixture();

        String selectedColor = (String) page.evalOnSelector("#tab-selected", "el => getComputedStyle(el).color");
        String unselectedColor = (String) page.evalOnSelector("#tab-unselected", "el => getComputedStyle(el).color");
        String selectedBorder =
                (String) page.evalOnSelector("#tab-selected", "el => getComputedStyle(el).borderBottomColor");
        String unselectedBorder =
                (String) page.evalOnSelector("#tab-unselected", "el => getComputedStyle(el).borderBottomColor");

        assertThat(selectedColor).isNotEqualTo(unselectedColor);
        assertThat(selectedBorder).isNotEqualTo(unselectedBorder);
    }
}
