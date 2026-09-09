package org.peekaboot.backend.domain.runtime;

/** A share as the dashboard shows it: a percentage rounded to two decimals, zero for an unknown whole. */
final class Percent {

    private Percent() {}

    static double of(long part, long whole) {
        double percent = whole > 0 ? (double) part / whole * 100.0 : 0.0;
        return Math.round(percent * 100.0) / 100.0;
    }
}
