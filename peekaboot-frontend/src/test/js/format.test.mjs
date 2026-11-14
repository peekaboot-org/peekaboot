import assert from 'node:assert/strict';
import test from 'node:test';

import {
    formatBytes,
    formatCount,
    formatDurationMs,
    formatInterval,
    formatMeasurementValue,
    formatMetricValue,
    formatTileValue,
    formatTimelineTicks
} from '../../main/resources/META-INF/peekaboot/ui/shared/format.js';

const en = {locale: 'en-US'};
const de = {locale: 'de-DE'};

test('an interval climbs the unit ladder with one decimal', () => {
    assert.equal(formatInterval(90000), '1.5m');
    assert.equal(formatInterval(172800000), '2d');
});

test('a count is grouped in the locale it is given', () => {
    assert.equal(formatCount(1234, 'row', de), '1.234 rows');
});

test('a duration that rounds up to the size of the next unit is shown in that unit', () => {
    assert.equal(formatDurationMs(999.4, en), '999ms');
    assert.equal(formatDurationMs(999.6, en), '1.00s');
    assert.equal(formatDurationMs(59994, en), '59.99s');
    assert.equal(formatDurationMs(59996, en), '1.00m');
    assert.equal(formatDurationMs(3599999, en), '1.00h');
    assert.equal(formatDurationMs(86399999, en), '1.00d');
});

test('a long duration is shown in hours and days', () => {
    assert.equal(formatDurationMs(5400000, en), '1.50h');
    assert.equal(formatDurationMs(172800000, en), '2.00d');
    assert.equal(formatDurationMs(86400000000, en), '1000.00d');
});

test('a duration below a millisecond says so', () => {
    assert.equal(formatDurationMs(0, en), '<1ms');
    assert.equal(formatDurationMs(0.9, en), '<1ms');
    assert.equal(formatDurationMs(1, en), '1ms');
});

test('a duration that is no length of time is a dash', () => {
    assert.equal(formatDurationMs(NaN, en), '-');
    assert.equal(formatDurationMs(Infinity, en), '-');
    assert.equal(formatDurationMs(-5, en), '-');
    assert.equal(formatDurationMs(null, en), '-');
    assert.equal(formatDurationMs(undefined, en), '-');
});

test('a size that rounds up to the size of the next unit is shown in that unit', () => {
    assert.equal(formatBytes(1023, en), '1023 B');
    assert.equal(formatBytes(1023.6, en), '1.00 KB');
    assert.equal(formatBytes(1048575, en), '1.00 MB');
    assert.equal(formatBytes(1536, en), '1.50 KB');
});

test('a size has the decimals of the figure it shows', () => {
    assert.equal(formatBytes(9.99 * 1024, en), '9.99 KB');
    assert.equal(formatBytes(9.996 * 1024, en), '10.0 KB');
    assert.equal(formatBytes(99.94 * 1024, en), '99.9 KB');
    assert.equal(formatBytes(99.96 * 1024, en), '100 KB');
    assert.equal(formatBytes(0.5, en), '0.50 B');
});

test('a size past the largest unit stays in it', () => {
    assert.equal(formatBytes(2048 * 1024 ** 4, en), '2048 TB');
});

test('a size that is no amount is a dash, and nothing is 0 B', () => {
    assert.equal(formatBytes(0, en), '0 B');
    assert.equal(formatBytes(NaN, en), '-');
    assert.equal(formatBytes(Infinity, en), '-');
    assert.equal(formatBytes(-1, en), '-');
    assert.equal(formatBytes(null, en), '-');
});

test('the ticks of a timeline share the unit its length is shown in', () => {
    assert.deepEqual(formatTimelineTicks(240000, en), ['0m', '1.00m', '2.00m', '3.00m', '4.00m']);
    assert.deepEqual(formatTimelineTicks(999.6, en), ['0s', '0.25s', '0.50s', '0.75s', '1.00s']);
    assert.deepEqual(formatTimelineTicks(7200000, en), ['0h', '0.50h', '1.00h', '1.50h', '2.00h']);
    assert.deepEqual(formatTimelineTicks(3, en), ['0ms', '0.8ms', '1.5ms', '2.3ms', '3.0ms']);
});

test('a meter statistic in seconds is shown by its magnitude, with its sign', () => {
    assert.equal(formatMeasurementValue(-0.5, 'seconds', en), '-500.00 ms');
    assert.equal(formatMeasurementValue(0.0005, 'seconds', en), '500 µs');
    assert.equal(formatMeasurementValue(2, 'seconds', en), '2.000 s');
    assert.equal(formatMeasurementValue(-2, 'seconds', en), '-2.000 s');
    assert.equal(formatMeasurementValue(0, 'seconds', en), '0 µs');
});

test('a meter statistic in seconds that rounds up to the next unit is shown in that unit', () => {
    assert.equal(formatMeasurementValue(0.0009996, 'seconds', en), '1.00 ms');
    assert.equal(formatMeasurementValue(0.999996, 'seconds', en), '1.000 s');
});

test('a meter statistic in bytes is a size, and one that is missing is a dash', () => {
    assert.equal(formatMeasurementValue(1536, 'bytes', en), '1.50 KB');
    assert.equal(formatMeasurementValue(null, 'seconds', en), '-');
    assert.equal(formatMeasurementValue(NaN, 'bytes', en), '-');
});

test('a meter statistic in any other unit keeps the precision of a small value', () => {
    assert.equal(formatMeasurementValue(1234.5678, 'rows', en), '1,234.57');
    assert.equal(formatMeasurementValue(0.25, undefined, en), '0.25');
    assert.equal(formatMeasurementValue(0.00012345, undefined, en), '1.23e-4');
    assert.equal(formatMeasurementValue(1234.5, 'rows', de), '1.234,5');
});

test('a duration, a size and a timeline tick take the decimal mark of the locale', () => {
    assert.equal(formatDurationMs(1500, de), '1,50s');
    assert.equal(formatDurationMs(5400000, de), '1,50h');
    assert.equal(formatBytes(1536, de), '1,50 KB');
    assert.deepEqual(formatTimelineTicks(1200, de), ['0s', '0,30s', '0,60s', '0,90s', '1,20s']);
    assert.deepEqual(formatTimelineTicks(3, de), ['0ms', '0,8ms', '1,5ms', '2,3ms', '3,0ms']);
});

test('a figure followed by a unit is never grouped', () => {
    assert.equal(formatDurationMs(86400000000, de), '1000,00d');
    assert.equal(formatBytes(1023, de), '1023 B');
    assert.equal(formatMeasurementValue(12345.678, 'seconds', en), '12345.678 s');
    assert.equal(formatMetricValue(1234.5, 'percent', en), '1234.5%');
    assert.equal(formatMetricValue(1500, 'persec', en), '1500/s');
});

// 59.995 and 1.005 are a hair below the tie as doubles: Intl alone would show 60.00 and 1.01.
test('a figure is rounded once, for the unit it is put in and for the text', () => {
    assert.equal(formatDurationMs(59995, en), '59.99s');
    assert.equal(formatBytes(1.005 * 1024, en), '1.00 KB');
});

test('a metric value is written in the locale, whatever its unit', () => {
    assert.equal(formatMetricValue(12.5, 'percent', de), '12,5%');
    assert.equal(formatMetricValue(42, 'percent', en), '42.0%');
    assert.equal(formatMetricValue(1536, 'bytes', de), '1,50 KB');
    assert.equal(formatMetricValue(1500, 'millis', de), '1,50s');
    assert.equal(formatMetricValue(2048, 'bytes-persec', de), '2,00 KB/s');
    assert.equal(formatMetricValue(0.25, 'persec', de), '0,25/s');
    assert.equal(formatMetricValue(1234.5, 'count', de), '1.234,50');
});

test('a rate keeps two significant digits and no trailing zero', () => {
    assert.equal(formatMetricValue(12.345, 'persec', en), '12/s');
    assert.equal(formatMetricValue(0.0012345, 'persec', en), '0.0012/s');
    assert.equal(formatMetricValue(2, 'persec', en), '2/s');
    assert.equal(formatMetricValue(0, 'persec', en), '0/s');
});

test('a tile value is written in the locale', () => {
    assert.equal(formatTileValue(1500, 'duration', de), '1,50s');
    assert.equal(formatTileValue(1536, 'bytes', de), '1,50 KB');
});

test('a meter statistic takes the decimal mark of the locale', () => {
    assert.equal(formatMeasurementValue(-0.5, 'seconds', de), '-500,00 ms');
    assert.equal(formatMeasurementValue(2, 'seconds', de), '2,000 s');
    assert.equal(formatMeasurementValue(1536, 'bytes', de), '1,50 KB');
});
