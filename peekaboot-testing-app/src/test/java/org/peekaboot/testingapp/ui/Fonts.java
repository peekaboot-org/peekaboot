package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import java.util.Map;

/**
 * The webfont gate every geometry assertion sits behind. Peekaboot serves Geist from its own
 * jar, so a measurement taken while the face is still loading measures the system fallback
 * and the assertion still passes - the failure mode this exists to remove. Awaited at the
 * three points a surface first becomes measurable rather than in each test: the dashboard
 * after its render, a host page after the bar has enhanced itself, and the overlay once its
 * loading placeholder is gone.
 */
final class Fonts {

    /** Long enough for a cold jar-served face, short enough to fail inside the suite's own runtime. */
    private static final double LOAD_TIMEOUT_MS = 10_000;

    static final String SANS = "Geist";
    static final String MONO = "Geist Mono";

    /**
     * Loads {@code family}, then measures {@code selector}'s own text twice: once as the
     * cascade renders it, once with {@code family} removed from that element's font stack.
     * Equal widths mean the page is painting the fallback whatever it asks for. {@code root}
     * is the document or a shadow root, so a shadow surface is measured in the scope it
     * really renders in. A Range is used rather than the element box because a block element
     * measures its container, not its glyphs.
     */
    static final String PROBE = """
            async (root, args) => {
                const [family, selector] = args;
                const node = root.querySelector(selector);
                const style = getComputedStyle(node);
                const declared = style.fontFamily;
                await document.fonts.load(style.fontSize + ' "' + family + '"');
                const range = document.createRange();
                range.selectNodeContents(node);
                const painted = range.getBoundingClientRect().width;
                const inlineBefore = node.style.fontFamily;
                node.style.fontFamily = declared.split(',')
                    .filter(entry => entry.trim().replace(/['"]/g, '') !== family)
                    .join(',');
                const withoutTheFamily = range.getBoundingClientRect().width;
                node.style.fontFamily = inlineBefore;
                return {
                    loadedFace: [...document.fonts].some(face =>
                        face.family.replace(/['"]/g, '') === family && face.status === 'loaded'),
                    paintedWidth: painted,
                    fallbackWidth: withoutTheFamily,
                    firstDeclared: declared.split(',')[0].trim().replace(/['"]/g, '')
                };
            }
            """;

    private Fonts() {}

    /**
     * Waits until no face is loading. Not {@code document.fonts.ready}: that promise re-arms
     * every time another face starts loading, and awaiting it through {@code page.evaluate}
     * carries no timeout at all, so a woff2 that never arrives hangs the build with no output.
     */
    static void awaitReady(Page page) {
        page.waitForFunction(
                "() => document.fonts.status === 'loaded'",
                null,
                new Page.WaitForFunctionOptions().setTimeout(LOAD_TIMEOUT_MS));
    }

    /** Whether a {@link #PROBE} result shows its node painting the probed family rather than the fallback. */
    static boolean paintsTheProbedFamily(Map<String, Object> probe) {
        return ((Number) probe.get("paintedWidth")).doubleValue()
                != ((Number) probe.get("fallbackWidth")).doubleValue();
    }
}
