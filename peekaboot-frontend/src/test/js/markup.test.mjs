import assert from 'node:assert/strict';
import test from 'node:test';

import {highlightParts} from '../../main/resources/META-INF/peekaboot/ui/shared/markup.js';

test('every match is a part of its own and the rest stays as it was written', () => {
    assert.deepEqual(highlightParts('a<b>a', 'a'),
        [{text: 'a', match: true}, {text: '<b>', match: false}, {text: 'a', match: true}]);
});

test('the query is a filter string, so its regex metacharacters mean themselves', () => {
    assert.deepEqual(highlightParts('a.b', '.'), [{text: 'a', match: false}, {text: '.', match: true}, {text: 'b', match: false}]);
    assert.deepEqual(highlightParts('a.b (c) [d]', '(c)'),
        [{text: 'a.b ', match: false}, {text: '(c)', match: true}, {text: ' [d]', match: false}]);
});

test('case is folded on the original string, so a longer lower-case form shifts no later match', () => {
    assert.deepEqual(highlightParts('İx-ab-ab', 'ab').filter(part => part.match).length, 2);
    assert.deepEqual(highlightParts('İx-ab-ab', 'ab'),
        [{text: 'İx-', match: false}, {text: 'ab', match: true}, {text: '-', match: false}, {text: 'ab', match: true}]);
    assert.deepEqual(highlightParts('Ärger', 'ä')[0], {text: 'Ä', match: true});
    assert.deepEqual(highlightParts('Ärger', 'ä'), [{text: 'Ä', match: true}, {text: 'rger', match: false}]);
});

test('without a query the text is one part that matched nothing', () => {
    assert.deepEqual(highlightParts('plain', ''), [{text: 'plain', match: false}]);
    assert.deepEqual(highlightParts('plain', undefined), [{text: 'plain', match: false}]);
});

test('a query that occurs nowhere leaves the text as one part', () => {
    assert.deepEqual(highlightParts('plain', 'x'), [{text: 'plain', match: false}]);
});

test('an empty part is left out', () => {
    assert.deepEqual(highlightParts('', 'a'), []);
    assert.deepEqual(highlightParts('', ''), []);
    assert.deepEqual(highlightParts('aa', 'a'), [{text: 'a', match: true}, {text: 'a', match: true}]);
});
