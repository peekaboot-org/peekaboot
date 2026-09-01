package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

    /** WCAG contrast ratio between an element's computed color and background-color. */
    private double contrastRatio(String selector) {
        return ((Number) page.evaluate("""
                (sel) => {
                    const style = getComputedStyle(document.querySelector(sel));
                    const parse = c => c.match(/\\d+(\\.\\d+)?/g).slice(0, 3).map(Number);
                    const luminance = ([r, g, b]) => {
                        const f = v => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
                        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
                    };
                    const text = luminance(parse(style.color));
                    const fill = luminance(parse(style.backgroundColor));
                    const [hi, lo] = text > fill ? [text, fill] : [fill, text];
                    return (hi + 0.05) / (lo + 0.05);
                }
                """, selector)).doubleValue();
    }

    /** The theme's --pk-danger, resolved to the same rgb() form computed styles use. */
    private String resolvedDangerFill() {
        return (String) page.evaluate("""
                () => {
                    const probe = document.createElement('div');
                    probe.style.backgroundColor = 'var(--pk-danger)';
                    document.body.appendChild(probe);
                    const resolved = getComputedStyle(probe).backgroundColor;
                    probe.remove();
                    return resolved;
                }
                """);
    }

    private String backgroundColor(String selector) {
        return (String) page.evalOnSelector(selector, "el => getComputedStyle(el).backgroundColor");
    }

    /**
     * Waits until the element's background-color holds still across consecutive reads -
     * .pk-btn transitions it over 0.2s, so a read right after a theme flip or hover/
     * un-hover would otherwise catch a mid-blend value and assert against noise.
     */
    private void awaitSettledBackground(String selector) {
        page.evalOnSelector(selector, """
                async el => {
                    const read = () => getComputedStyle(el).backgroundColor;
                    let previous = read();
                    for (let i = 0; i < 60; i++) {
                        await new Promise(resolve => setTimeout(resolve, 50));
                        const current = read();
                        if (current === previous) return;
                        previous = current;
                    }
                    throw new Error('background-color never settled: ' + read());
                }
                """);
    }

    /**
     * The Environment/Config "Secrets shown" pressed state pairs the --pk-danger fill
     * with the --pk-on-danger ink (dashboard.css, per the palette's fill/ink rule). That
     * pairing must hold in every state the button can be in - resting AND hovered, light
     * AND dark: components.css's generic .pk-btn hover rule outranks the pressed rule by
     * specificity, so an unguarded hover swaps the danger fill for the neutral
     * --pk-bg-hover while the ink stays --pk-on-danger - white on near-white in the
     * light theme, dark-on-dark in the dark one.
     */
    @Test
    void pressedSecretsToggleKeepsItsDangerFillAndReadableInkInEveryState() {
        openFixture();

        for (String theme : List.of("light", "dark")) {
            page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme);
            page.mouse().move(0, 0); // make sure nothing is hovered
            awaitSettledBackground("#unmask-pressed");

            assertThat(backgroundColor("#unmask-pressed"))
                    .as("resting fill is --pk-danger (%s theme)", theme)
                    .isEqualTo(resolvedDangerFill());
            assertThat(contrastRatio("#unmask-pressed"))
                    .as("resting ink/fill contrast (%s theme)", theme)
                    .isGreaterThanOrEqualTo(4.5);

            page.hover("#unmask-pressed");
            awaitSettledBackground("#unmask-pressed");

            assertThat(backgroundColor("#unmask-pressed"))
                    .as("hovered fill stays --pk-danger (%s theme)", theme)
                    .isEqualTo(resolvedDangerFill());
            assertThat(contrastRatio("#unmask-pressed"))
                    .as("hovered ink/fill contrast (%s theme)", theme)
                    .isGreaterThanOrEqualTo(4.5);
        }
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
