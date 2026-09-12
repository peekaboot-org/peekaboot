/**
 * Registers the bundled Geist faces with the document a shadow-rooted surface renders into.
 *
 * An @font-face rule cannot serve those surfaces. CSS scopes font family names to the tree
 * that declares them, with upward fallback only: a document-level rule is visible inside a
 * shadow tree, a rule declared inside one is ignored. Peekaboot contributes no document-level
 * CSS to a host page, so tokens.css reaches the dashboard and nothing else. Adding the faces
 * through the Font Loading API puts them where both shadow trees inherit them without adding
 * a rule to the host page's cascade, which is the isolation the toolbar and the overlay
 * promise (docs/ARCHITECTURE.md, peekaboot-frontend/README.md).
 *
 * The files are governed by the host's `font-src`, never `style-src`.
 */

const FACES = [
    {family: 'Geist', file: 'Geist-1.7.2.woff2'},
    {family: 'Geist Mono', file: 'GeistMono-1.7.2.woff2'}
];

// swap, where tokens.css uses optional: these faces are registered after the host page has
// loaded, so optional would licence the browser never to paint them at all, and the repaint a
// swap causes is confined to Peekaboot's own fixed-position surfaces, never host content.
const DESCRIPTORS = {weight: '100 900', style: 'normal', display: 'swap'};

let registered = false;

/** Idempotent per document: both entry points call it, and the overlay opens repeatedly. */
export function registerBundledFonts(basePath) {
    if (registered) return;
    registered = true;
    FACES.forEach(face => addFace(face, basePath));
}

function addFace({family, file}, basePath) {
    // The dashboard already declares these faces in tokens.css, and this module runs there
    // too (main.js imports the overlay statically); a second copy would match and fetch
    // alongside them for nothing.
    if (documentDeclares(family)) return;
    try {
        // Quoted: an unquoted url() token cannot carry whitespace or parens, which a host's
        // context path can.
        const font = new FontFace(family, `url("${basePath}/ui/vendor/geist/${file}")`, DESCRIPTORS);
        document.fonts.add(font);
        // A missing or blocked file must not surface as an unhandled rejection on a page
        // Peekaboot does not own.
        font.load().catch(() => {});
    } catch (e) {
        // InvalidModificationError (a CSS-connected face of the same name), a browser with
        // no FontFace, a document with no font set: the surface renders in the host's own
        // font rather than not at all.
        console.warn('Peekaboot: could not register its bundled font', e);
    }
}

/** Quotes are stripped: a face declared as 'Geist' reports its family back quoted in some browsers. */
function documentDeclares(family) {
    for (const face of document.fonts) {
        if (face.family.replace(/['"]/g, '') === family) return true;
    }
    return false;
}
