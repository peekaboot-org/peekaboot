import js from '@eslint/js';
import globals from 'globals';
import { importX } from 'eslint-plugin-import-x';

const UI = 'src/main/resources/META-INF/peekaboot/ui';

export default [
    // Load-bearing, not hygiene: uplot's minified bundle reports seven errors of its own
    // under these rules, so without this the gate fails on code we do not maintain.
    { ignores: [`${UI}/vendor/**`] },

    js.configs.recommended,
    importX.flatConfigs.recommended,

    {
        languageOptions: {
            ecmaVersion: 2024,
            // Not a default worth inheriting: under script scope an unimported identifier
            // reads as a possible implicit global and no-undef says nothing. Module scope
            // is what makes this gate catch a missing import at all.
            sourceType: 'module',
            globals: globals.browser,
        },
    },

    {
        // index.html and PeekabootErrorView load these three with a plain <script> tag, so
        // an import statement in them would throw in the browser. Linting them as modules
        // would call that clean.
        files: [
            `${UI}/assets/theme-boot.js`,
            `${UI}/dashboard/boot-recovery.js`,
            `${UI}/error-page/reveal.js`,
        ],
        languageOptions: { sourceType: 'script' },
    },
];
