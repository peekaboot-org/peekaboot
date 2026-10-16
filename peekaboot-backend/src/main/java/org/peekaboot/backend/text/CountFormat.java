package org.peekaboot.backend.text;

/** A count with its unit, singular for one ({@code "1 line"}, {@code "2 lines"}). Units pluralize with a trailing s. */
public final class CountFormat {

    private CountFormat() {}

    public static String of(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }
}
