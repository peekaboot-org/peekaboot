const UI = 'src/main/resources/META-INF/peekaboot/ui';

export default {
    extends: ['stylelint-config-recommended'],
    plugins: ['stylelint-value-no-unknown-custom-properties'],

    // uplot's stylesheet happens to be clean under these rules today, unlike its script,
    // which reports seven errors. Excluded anyway: what a vendored bundle does is not a
    // finding we can act on, and the next version bump should not be able to fail a build.
    ignoreFiles: [`${UI}/vendor/**`],

    rules: {
        // Fires 12 times on this design system, every one of them a false alarm about
        // rules that are ordered for readability. Left on, it is what gets the gate
        // switched off on a bad day.
        'no-descending-specificity': null,

        // importFrom takes an explicit list and no globs: a token defined in a file that
        // is not named here reads as unknown. Paths resolve from the working directory,
        // which is this module for both build systems.
        'csstools/value-no-unknown-custom-properties': [
            true,
            {
                importFrom: [
                    `${UI}/assets/tokens.css`,
                    'js-defined-css-tokens.json',
                ],
            },
        ],
    },
};
