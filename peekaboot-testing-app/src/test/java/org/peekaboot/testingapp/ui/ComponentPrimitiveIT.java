package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
     * .pk-btn sets every other text property but not font-family, so without one a button
     * renders in the UA's own button font instead of the page's.
     */
    @Test
    void buttonsRenderInTheUiFont() {
        openFixture();

        String buttonFont = (String) page.evalOnSelector("#btn-pressed", "el => getComputedStyle(el).fontFamily");
        String parentFont =
                (String) page.evalOnSelector("#btn-pressed", "el => getComputedStyle(el.parentElement).fontFamily");

        assertThat(buttonFont).isEqualTo(parentFont);
    }

    /**
     * Every badge variant's ink/fill pair must clear WCAG AA's 4.5:1 in BOTH themes,
     * measured from the resolved styles rather than pinned hexes so a future palette
     * tweak that regresses one variant fails here instead of in a screenshot.
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
     * The trace-detail overlay renders ink of its own - the row-count chips, the error chip
     * (fixed to an open row's own ground, .pk-gantt-name, the way spans.js actually places
     * it) and the details panel's error, tag keys and values - and tabStrip() its count
     * pill; each owes the same 4.5:1 as a badge in both themes. The tag key doubles as the
     * guard for muted ink on the panel's --pk-bg-alt ground.
     */
    @Test
    void traceDetailInkClearsAaContrastInBothThemes() {
        openFixture();

        for (String theme : List.of("light", "dark")) {
            page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme);
            for (String pill : List.of(
                    "span-row-count",
                    "query-rows",
                    "span-error",
                    "span-error-chip",
                    "span-tag-key",
                    "span-tag-value",
                    "tab-count")) {
                assertThat(contrastRatio("#" + pill))
                        .as("%s ink/fill contrast (%s theme)", pill, theme)
                        .isGreaterThanOrEqualTo(4.5);
            }
        }
    }

    /**
     * A span's kind sets its dot's fill. Producer and consumer are visually distinct kinds
     * even though neither is the trace's primary server/client axis, so each reads its own
     * token. The server dot takes the higher-contrast --pk-primary-text rather than the
     * bar's plain --pk-primary: an 8px dot needs more contrast against the page than a wide
     * bar does, and the legend and every row share the one dot rule (trace-detail.css).
     */
    @Test
    void kindDotsUseDistinctFills() {
        openFixture();

        assertThat(backgroundColor("#kind-dot-producer"))
                .as("producer and consumer dots differ")
                .isNotEqualTo(backgroundColor("#kind-dot-consumer"));
        assertThat(backgroundColor("#kind-dot-server"))
                .as("server dot uses the text-tuned green, not the bar's plain fill")
                .isEqualTo(resolvedVar("--pk-primary-text"));
    }

    /**
     * A row count is a fact about the query, not a verdict, so the Queries tab's count
     * takes the span tree's neutral chip instead of its own success-green text.
     */
    @Test
    void queryRowCountsRenderAsTheSpanTreesNeutralChip() {
        openFixture();

        assertThat(backgroundColor("#query-rows")).isEqualTo(backgroundColor("#span-row-count"));
        String queryColor = (String) page.evalOnSelector("#query-rows", "el => getComputedStyle(el).color");
        String spanColor = (String) page.evalOnSelector("#span-row-count", "el => getComputedStyle(el).color");
        assertThat(queryColor).isEqualTo(spanColor);
    }

    @Test
    void collapsedGroupListIsNotVisible() {
        openFixture();

        assertThat(page.isVisible("#group-list")).isFalse();
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

    /** The width comes from the fixture's own inline style; the clip and the colour are the sheet's. */
    @Test
    void meterFillIsClippedByItsTrackAndColouredByItsVariant() {
        openFixture();

        String baseColor =
                (String) page.evalOnSelector("#meter-fill-base", "el => getComputedStyle(el).backgroundColor");
        String dangerColor =
                (String) page.evalOnSelector("#meter-fill-danger", "el => getComputedStyle(el).backgroundColor");
        assertThat(dangerColor).isNotEqualTo(baseColor);

        String overflow = (String) page.evalOnSelector("#meter-danger", "el => getComputedStyle(el).overflow");
        assertThat(overflow).isEqualTo("hidden");
    }

    /**
     * DISTRIBUTION_SUMMARY, the longest meter type and one no testing-app meter carries, fits its
     * badge column whole.
     */
    @Test
    void theLongestMeterTypeBadgeFitsItsColumn() {
        openFixture();

        assertThat(meterTypeBadgesOutsideTheirColumn("#meter-group")).isEmpty();
    }

    /**
     * WCAG contrast ratio between an element's computed color and the effective fill
     * behind it: its own background-color, or - where that is fully transparent, like
     * a details panel's tag key - the nearest ancestor's.
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

    private String backgroundColor(String selector) {
        return (String) page.evalOnSelector(selector, "el => getComputedStyle(el).backgroundColor");
    }

    /** Moves the pointer clear of every button and waits for the resting paint. */
    private void awaitResting(String selector) {
        page.mouse().move(0, 0);
        awaitSettledPaint(selector, false);
    }

    /** Hovers {@code selector} and waits for its hover cue to finish. */
    private void awaitHovered(String selector) {
        page.hover(selector);
        awaitSettledPaint(selector, true);
    }

    /**
     * Waits until the element is in the wanted hover state and every transition it started has
     * finished, so the read that follows sees the end state - .pk-btn transitions both its
     * background-color and its filter over 0.2s.
     *
     * <p>Equal consecutive reads are not proof of a settled paint: a transition that has not
     * advanced between two samples - one whose hover style has not landed yet, or one starved of
     * frames - satisfies that just as well as a finished one. The cue's own starting value is
     * brightness(1), which renders as the untouched fill, so sampling there makes the hovered
     * fill compare equal to the resting one.
     */
    private void awaitSettledPaint(String selector, boolean hovered) {
        page.evalOnSelector(selector, """
                async (el, hovered) => {
                    const deadline = Date.now() + 5000;
                    while (Date.now() < deadline) {
                        if (el.matches(':hover') === hovered) {
                            // finished rejects on a transition a later style change replaces, and
                            // never resolves while the timeline is starved, so the deadline rather
                            // than the promise bounds this wait.
                            const done = Promise.all(el.getAnimations().map(a => a.finished.catch(() => {})));
                            await Promise.race([done, new Promise(r => setTimeout(r, deadline - Date.now()))]);
                            if (el.matches(':hover') === hovered && el.getAnimations().length === 0) return;
                        }
                        await new Promise(resolve => setTimeout(resolve, 50));
                    }
                    throw new Error('paint never settled: hover=' + el.matches(':hover')
                            + ' filter=' + getComputedStyle(el).filter);
                }
                """, hovered);
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
                awaitResting(button);
                Map<String, Object> resting = renderedInkAndFill(button);
                assertThat(((Number) resting.get("ratio")).doubleValue())
                        .as("%s resting ink/fill contrast (%s theme)", button, theme)
                        .isGreaterThanOrEqualTo(4.5);

                awaitHovered(button);
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
            awaitResting("#unmask-pressed");

            assertThat(backgroundColor("#unmask-pressed"))
                    .as("resting fill is --pk-danger (%s theme)", theme)
                    .isEqualTo(resolvedVar(null, "--pk-danger"));
            assertThat(contrastRatio("#unmask-pressed"))
                    .as("resting ink/fill contrast (%s theme)", theme)
                    .isGreaterThanOrEqualTo(4.5);

            awaitHovered("#unmask-pressed");

            assertThat(backgroundColor("#unmask-pressed"))
                    .as("hovered fill stays --pk-danger (%s theme)", theme)
                    .isEqualTo(resolvedVar(null, "--pk-danger"));
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
