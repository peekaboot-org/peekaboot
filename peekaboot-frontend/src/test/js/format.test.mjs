import assert from 'node:assert/strict';
import test from 'node:test';

import {formatCount, formatInterval} from '../../main/resources/META-INF/peekaboot/ui/shared/format.js';

test('an interval climbs the unit ladder with one decimal', () => {
    assert.equal(formatInterval(90000), '1.5m');
    assert.equal(formatInterval(172800000), '2d');
});

test('a count is grouped in the locale it is given', () => {
    assert.equal(formatCount(1234, 'row', {locale: 'de-DE'}), '1.234 rows');
});
