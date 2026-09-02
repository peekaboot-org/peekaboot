package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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

    /**
     * Every badge variant's ink/fill pair must clear WCAG AA's 4.5:1 in BOTH themes,
     * measured from the resolved styles rather than pinned hexes so a future palette
     * tweak that regresses one variant fails here instead of in a screenshot. --info
     * is the variant that prompted this sweep: its old light-theme fill passed AA on
     * paper (4.77:1) while sitting visibly darker than every sibling fill.
     */
    @Test
    void badgeVariantInkClearsAaContrastInBothThemes() {
        openFixture();

        for (String theme : List.of("light", "dark")) {
            page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme);
            for (String badge :
                    List.of("badge-ok", "badge-warn", "badge-error", "badge-error-soft", "badge-info", "badge-muted")) {
                assertThat(contrastRatio("#" + badge))
                        .as("%s ink/fill contrast (%s theme)", badge, theme)
                        .isGreaterThanOrEqualTo(4.5);
            }
        }
    }

    /**
     * The trace-detail overlay renders pills of its own - the gantt kind pills, the
     * span tag badges and the tab-strip count - that are badges in all but class name,
     * so they owe the same 4.5:1 in both themes. The tag badge doubles as the guard for
     * muted/accent ink on the --pk-bg-hover surface, the lightest fill any text sits on.
     */
    @Test
    void traceDetailPillInkClearsAaContrastInBothThemes() {
        openFixture();

        for (String theme : List.of("light", "dark")) {
            page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme);
            for (String pill : List.of(
                    "kind-server",
                    "kind-client",
                    "kind-internal",
                    "kind-producer",
                    "tag-badge",
                    "tag-badge-key",
                    "tab-count")) {
                assertThat(contrastRatio("#" + pill))
                        .as("%s ink/fill contrast (%s theme)", pill, theme)
                        .isGreaterThanOrEqualTo(4.5);
            }
        }
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

    /**
     * WCAG contrast ratio between an element's computed color and the effective fill
     * behind it: its own background-color, or - where that is fully transparent, like
     * the tag badge's key span - the nearest ancestor's.
     */
    private double contrastRatio(String selector) {
        return ((Number) page.evaluate("""
                (sel) => {
                    const parse = c => c.match(/\\d+(\\.\\d+)?/g).map(Number);
                    const luminance = ([r, g, b]) => {
                        const f = v => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
                        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
                    };
                    const start = document.querySelector(sel);
                    const text = luminance(parse(getComputedStyle(start).color).slice(0, 3));
                    let fill = null;
                    for (let el = start; el; el = el.parentElement) {
                        const c = parse(getComputedStyle(el).backgroundColor);
                        if (c.length < 4 || c[3] > 0) { fill = luminance(c.slice(0, 3)); break; }
                    }
                    if (fill === null) throw new Error('no opaque background behind ' + sel);
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
     * Waits until the element's background-color and filter hold still across consecutive
     * reads - .pk-btn transitions both over 0.2s, so a read right after a theme flip or
     * hover/un-hover would otherwise catch a mid-blend value and assert against noise.
     */
    private void awaitSettledPaint(String selector) {
        page.evalOnSelector(selector, """
                async el => {
                    const read = () => getComputedStyle(el).backgroundColor + ' ' + getComputedStyle(el).filter;
                    let previous = read();
                    for (let i = 0; i < 60; i++) {
                        await new Promise(resolve => setTimeout(resolve, 50));
                        const current = read();
                        if (current === previous) return;
                        previous = current;
                    }
                    throw new Error('paint never settled: ' + read());
                }
                """);
    }

    /**
     * The ink/fill pair as actually rendered: a brightness() filter - the pressed-button
     * hover cue - multiplies every channel of ink and fill alike in sRGB space (the CSS
     * shorthand filters operate in sRGB, clamped at white), which the computed color/
     * backgroundColor that {@link #contrastRatio} reads never reflects.
     */
    private Map<String, Object> renderedInkAndFill(String selector) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = (Map<String, Object>) page.evaluate("""
                (sel) => {
                    const parse = c => c.match(/\\d+(\\.\\d+)?/g).map(Number);
                    const luminance = ([r, g, b]) => {
                        const f = v => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
                        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
                    };
                    const style = getComputedStyle(document.querySelector(sel));
                    const brightness = style.filter.match(/brightness\\((\\d*\\.?\\d+)\\)/);
                    const k = brightness ? Number(brightness[1]) : 1;
                    const apply = rgb => rgb.slice(0, 3).map(v => Math.min(255, v * k));
                    const ink = luminance(apply(parse(style.color)));
                    const fill = apply(parse(style.backgroundColor));
                    const [hi, lo] = [ink, luminance(fill)].sort((a, b) => b - a);
                    return {fill: fill.join(','), ratio: (hi + 0.05) / (lo + 0.05)};
                }
                """, selector);
        return rendered;
    }

    /**
     * Pressed buttons are excluded from the generic hover fill swap (their fill IS their
     * state - see components.css), so they carry a hover cue of their own: a per-theme
     * brightness() filter that shifts ink and fill together instead of swapping the fill
     * out from under its ink. The cue must actually show (the rendered fill changes) and
     * the rendered pairing must still clear AA's 4.5:1 in every combination - the green
     * selection fill and the unmask toggle's danger fill, resting and hovered, both themes.
     */
    @Test
    void pressedButtonsShowAHoverCueAndKeepAaContrastInBothThemes() {
        openFixture();

        for (String theme : List.of("light", "dark")) {
            page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme);
            for (String button : List.of("#btn-pressed", "#unmask-pressed")) {
                page.mouse().move(0, 0); // make sure nothing is hovered
                awaitSettledPaint(button);
                Map<String, Object> resting = renderedInkAndFill(button);
                assertThat(((Number) resting.get("ratio")).doubleValue())
                        .as("%s resting ink/fill contrast (%s theme)", button, theme)
                        .isGreaterThanOrEqualTo(4.5);

                page.hover(button);
                awaitSettledPaint(button);
                Map<String, Object> hovered = renderedInkAndFill(button);
                assertThat(hovered.get("fill"))
                        .as("%s hover shifts the rendered fill (%s theme)", button, theme)
                        .isNotEqualTo(resting.get("fill"));
                assertThat(((Number) hovered.get("ratio")).doubleValue())
                        .as("%s hovered ink/fill contrast (%s theme)", button, theme)
                        .isGreaterThanOrEqualTo(4.5);
            }
        }
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
            awaitSettledPaint("#unmask-pressed");

            assertThat(backgroundColor("#unmask-pressed"))
                    .as("resting fill is --pk-danger (%s theme)", theme)
                    .isEqualTo(resolvedDangerFill());
            assertThat(contrastRatio("#unmask-pressed"))
                    .as("resting ink/fill contrast (%s theme)", theme)
                    .isGreaterThanOrEqualTo(4.5);

            page.hover("#unmask-pressed");
            awaitSettledPaint("#unmask-pressed");

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
