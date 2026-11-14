import assert from 'node:assert/strict';
import test from 'node:test';

import {
    formatBytes,
    formatCount,
    formatDurationMs,
    formatInterval,
    formatMeasurementValue,
    formatTimelineTicks
} from '../../main/resources/META-INF/peekaboot/ui/shared/format.js';

const en = {locale: 'en-US'};

test('an interval climbs the unit ladder with one decimal', () => {
    assert.equal(formatInterval(90000), '1.5m');
    assert.equal(formatInterval(172800000), '2d');
});

test('a count is grouped in the locale it is given', () => {
    assert.equal(formatCount(1234, 'row', {locale: 'de-DE'}), '1.234 rows');
});

test('a duration that rounds up to the size of the next unit is shown in that unit', () => {
    assert.equal(formatDurationMs(999.4), '999ms');
    assert.equal(formatDurationMs(999.6), '1.00s');
    assert.equal(formatDurationMs(59994), '59.99s');
    assert.equal(formatDurationMs(59996), '1.00m');
    assert.equal(formatDurationMs(3599999), '1.00h');
    assert.equal(formatDurationMs(86399999), '1.00d');
});

test('a long duration is shown in hours and days', () => {
    assert.equal(formatDurationMs(5400000), '1.50h');
    assert.equal(formatDurationMs(172800000), '2.00d');
    assert.equal(formatDurationMs(86400000000), '1000.00d');
});

test('a duration below a millisecond says so', () => {
    assert.equal(formatDurationMs(0), '<1ms');
    assert.equal(formatDurationMs(0.9), '<1ms');
    assert.equal(formatDurationMs(1), '1ms');
});

test('a duration that is no length of time is a dash', () => {
    assert.equal(formatDurationMs(NaN), '-');
    assert.equal(formatDurationMs(Infinity), '-');
    assert.equal(formatDurationMs(-5), '-');
    assert.equal(formatDurationMs(null), '-');
    assert.equal(formatDurationMs(undefined), '-');
});

test('a size that rounds up to the size of the next unit is shown in that unit', () => {
    assert.equal(formatBytes(1023), '1023 B');
    assert.equal(formatBytes(1023.6), '1.00 KB');
    assert.equal(formatBytes(1048575), '1.00 MB');
    assert.equal(formatBytes(1536), '1.50 KB');
});

test('a size has the decimals of the figure it shows', () => {
    assert.equal(formatBytes(9.99 * 1024), '9.99 KB');
    assert.equal(formatBytes(9.996 * 1024), '10.0 KB');
    assert.equal(formatBytes(99.94 * 1024), '99.9 KB');
    assert.equal(formatBytes(99.96 * 1024), '100 KB');
    assert.equal(formatBytes(0.5), '0.50 B');
});

test('a size past the largest unit stays in it', () => {
    assert.equal(formatBytes(2048 * 1024 ** 4), '2048 TB');
});

test('a size that is no amount is a dash, and nothing is 0 B', () => {
    assert.equal(formatBytes(0), '0 B');
    assert.equal(formatBytes(NaN), '-');
    assert.equal(formatBytes(Infinity), '-');
    assert.equal(formatBytes(-1), '-');
    assert.equal(formatBytes(null), '-');
});

test('the ticks of a timeline share the unit its length is shown in', () => {
    assert.deepEqual(formatTimelineTicks(240000), ['0m', '1.00m', '2.00m', '3.00m', '4.00m']);
    assert.deepEqual(formatTimelineTicks(999.6), ['0s', '0.25s', '0.50s', '0.75s', '1.00s']);
    assert.deepEqual(formatTimelineTicks(7200000), ['0h', '0.50h', '1.00h', '1.50h', '2.00h']);
    assert.deepEqual(formatTimelineTicks(3), ['0ms', '0.8ms', '1.5ms', '2.3ms', '3.0ms']);
});

test('a meter statistic in seconds is shown by its magnitude, with its sign', () => {
    assert.equal(formatMeasurementValue(-0.5, 'seconds'), '-500.00 ms');
    assert.equal(formatMeasurementValue(0.0005, 'seconds'), '500 µs');
    assert.equal(formatMeasurementValue(2, 'seconds'), '2.000 s');
    assert.equal(formatMeasurementValue(-2, 'seconds'), '-2.000 s');
    assert.equal(formatMeasurementValue(0, 'seconds'), '0 µs');
});

test('a meter statistic in seconds that rounds up to the next unit is shown in that unit', () => {
    assert.equal(formatMeasurementValue(0.0009996, 'seconds'), '1.00 ms');
    assert.equal(formatMeasurementValue(0.999996, 'seconds'), '1.000 s');
});

test('a meter statistic in bytes is a size, and one that is missing is a dash', () => {
    assert.equal(formatMeasurementValue(1536, 'bytes'), '1.50 KB');
    assert.equal(formatMeasurementValue(null, 'seconds'), '-');
    assert.equal(formatMeasurementValue(NaN, 'bytes'), '-');
});

test('a meter statistic in any other unit keeps the precision of a small value', () => {
    assert.equal(formatMeasurementValue(1234.5678, 'rows', en), '1,234.57');
    assert.equal(formatMeasurementValue(0.25, undefined, en), '0.25');
    assert.equal(formatMeasurementValue(0.00012345, undefined, en), '1.23e-4');
    assert.equal(formatMeasurementValue(1234.5, 'rows', {locale: 'de-DE'}), '1.234,5');
});
