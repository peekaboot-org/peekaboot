package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.problem.ApiProblemException;

class CursorsTest {

    @Test
    void aRowCursorReadsBackAsThePositionItNamed() {
        RowPosition trace = new RowPosition(42, 7, RowPosition.WHOLE_TRACE);
        RowPosition asyncRow = new RowPosition(43, 7, 43);

        assertThat(Cursors.row(Cursors.ofRow(trace))).isEqualTo(trace);
        assertThat(Cursors.row(Cursors.ofRow(asyncRow))).isEqualTo(asyncRow);
    }

    /** Base64url without padding, so it travels in a query string as it is. */
    @Test
    void aCursorIsUrlSafeText() {
        assertThat(Cursors.ofRow(new RowPosition(1, 1, RowPosition.WHOLE_TRACE)))
                .isEqualTo("dC4xLjEuLTE");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void noCursorAsksForTheFirstPage(String cursor) {
        assertThat(Cursors.row(cursor)).isNull();
    }

    /** Garbage, one base64 character, too few numbers, too many, a log cursor, a letter, and a 19-digit number, past the cap. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "not a cursor",
                "x",
                "dC4x",
                "dC4xLjE",
                "dC4xLjEuMC4w",
                "bC43",
                "dC5hLjEuMQ",
                "dC4xMjM0NTY3ODkwMTIzNDU2Nzg5LjEuMQ"
            })
    void refusesWhatTheListingNeverHandedOut(String cursor) {
        assertRefused(cursor, () -> Cursors.row(cursor));
    }

    /**
     * Values that decode but that the listing never writes. Negative stamps, a padded number with a negative
     * zero and an entry below a trace's own row, a padded number alone, a leading space, and base64 with
     * spare bits set.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dC4tNS4tNS4tNQ", "dC4wMS4tMC4tNw", "dC4wMS4xLi0x", " dC4xLjEuLTE", "dC4xLjEuLTF"})
    void refusesAPositionTheListingNeverWrites(String cursor) {
        assertRefused(cursor, () -> Cursors.row(cursor));
    }

    @Test
    void aLogCursorReadsBackAsTheSequenceItNamed() {
        assertThat(Cursors.ofLog(7)).isEqualTo("bC43");
        assertThat(Cursors.log(Cursors.ofLog(7))).isEqualTo(7L);
        assertThat(Cursors.log(" ")).isNull();
    }

    @Test
    void eachEndpointRefusesTheOthersCursor() {
        String rowCursor = Cursors.ofRow(new RowPosition(1, 1, RowPosition.WHOLE_TRACE));

        assertThatThrownBy(() -> Cursors.log(rowCursor)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> Cursors.row(Cursors.ofLog(7))).isInstanceOf(ApiProblemException.class);
    }

    /** A negative sequence, a padded number, a negative zero, two numbers, none, a letter, and a 19-digit number, past the cap. */
    @ParameterizedTest
    @ValueSource(strings = {"bC4tMQ", "bC4wNw", "bC4tMA", "bC43LjE", "bC4", "bC5h", "bC4xMjM0NTY3ODkwMTIzNDU2Nzg5"})
    void refusesASequenceTheSearchNeverWrites(String cursor) {
        assertRefused(cursor, () -> Cursors.log(cursor));
    }

    private static void assertRefused(String cursor, ThrowingCallable read) {
        assertThatThrownBy(read).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.problem().parameter()).isEqualTo("cursor");
            assertThat(e.problem().detail())
                    .isEqualTo("Unknown cursor '" + cursor
                            + "'. Pass the next value of an earlier response from this endpoint as it was.");
        });
    }
}
