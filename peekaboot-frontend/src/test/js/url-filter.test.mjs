import assert from 'node:assert/strict';
import test from 'node:test';

import {textFilterState, urlState} from '../../main/resources/META-INF/peekaboot/ui/shared/url-filter.js';

/** A pager's URL state: a 0-based page shown 1-based in the URL, page one left out. */
function pagerOn(page) {
    const state = {page};
    const applied = [];
    const pager = urlState({
        keys: ['page'],
        read: urlParams => {
            const named = Number(urlParams.page);
            return {page: Number.isInteger(named) && named >= 1 ? named - 1 : 0};
        },
        apply: next => {
            applied.push(next);
            state.page = next.page;
        },
        params: () => (state.page === 0 ? {} : {page: String(state.page + 1)})
    });
    return {pager, applied};
}

function contextOn(urlParams, {urlIsAuthoritative = false} = {}) {
    const written = [];
    return {written, context: {urlParams, urlIsAuthoritative, setUrlParams: params => written.push(params)}};
}

test('a URL naming a key seeds the state and is rewritten canonically', () => {
    const {pager, applied} = pagerOn(3);
    const {written, context} = contextOn({page: 'abc'});

    pager.reconcile(context);

    assert.deepEqual(applied, [{page: 0}]);
    assert.deepEqual(written, [{}]);
});

test('an authoritative bare URL resets the state', () => {
    const {pager, applied} = pagerOn(3);
    const {written, context} = contextOn({}, {urlIsAuthoritative: true});

    pager.reconcile(context);

    assert.deepEqual(applied, [{page: 0}]);
    assert.deepEqual(written, [{}]);
});

test('a bare URL on a programmatic render keeps the state and writes it back', () => {
    const {pager, applied} = pagerOn(3);
    const {written, context} = contextOn({});

    pager.reconcile(context);

    assert.deepEqual(applied, []);
    assert.deepEqual(written, [{page: '4'}]);
});

test('a bare URL with default state writes nothing', () => {
    const {pager, applied} = pagerOn(0);
    const {written, context} = contextOn({});

    pager.reconcile(context);

    assert.deepEqual(applied, []);
    assert.deepEqual(written, []);
});

test('a context without URL params reads as a bare URL', () => {
    const {pager, applied} = pagerOn(0);
    const {written, context} = contextOn(undefined);

    pager.reconcile(context);

    assert.deepEqual(applied, []);
    assert.deepEqual(written, []);
});

test('a text filter is trimmed and an empty one leaves no q', () => {
    const {written, context} = contextOn({});

    textFilterState({value: ' x '}).save(context);
    textFilterState({value: '  '}).save(context);

    assert.deepEqual(written, [{q: 'x'}, {}]);
});

test('a text filter takes its value from the URL', () => {
    const input = {value: ''};
    const {written, context} = contextOn({q: 'abc'});

    textFilterState(input).reconcile(context);

    assert.equal(input.value, 'abc');
    assert.deepEqual(written, [{q: 'abc'}]);
});

test('a text filter that already says what the URL says keeps its untrimmed value', () => {
    const input = {value: 'abc '};
    const {context} = contextOn({q: 'abc'});

    textFilterState(input).reconcile(context);

    assert.equal(input.value, 'abc ');
});
