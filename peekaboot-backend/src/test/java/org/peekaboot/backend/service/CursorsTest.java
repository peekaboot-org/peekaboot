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

    private static final long NUMBERING = 7;

    @Test
    void aRowCursorReadsBackAsThePositionItNamed() {
        RowPosition trace = new RowPosition(42, 7, RowPosition.WHOLE_TRACE);
        RowPosition asyncRow = new RowPosition(43, 7, 43);

        assertThat(Cursors.row(Cursors.ofRow(NUMBERING, trace)).under(NUMBERING))
                .isEqualTo(trace);
        assertThat(Cursors.row(Cursors.ofRow(NUMBERING, asyncRow)).under(NUMBERING))
                .isEqualTo(asyncRow);
    }

    /** Base64url without padding, so it travels in a query string as it is. */
    @Test
    void aCursorIsUrlSafeText() {
        assertThat(Cursors.ofRow(NUMBERING, new RowPosition(1, 1, RowPosition.WHOLE_TRACE)))
                .isEqualTo("dC43LjEuMS4tMQ");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void noCursorAsksForTheFirstPage(String cursor) {
        assertThat(Cursors.row(cursor)).isNull();
    }

    /**
     * Garbage, one base64 character, too few numbers, too many, a log cursor, a letter for the numbering,
     * a 19-digit number past the cap, and a position from before this numbering existed ({@code t.1.1.-1}).
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "not a cursor",
                "x",
                "dC43LjEuMQ",
                "dC43LjEuMS4wLjA",
                "bC43LjU",
                "dC5hLjEuMS4tMQ",
                "dC4xMjM0NTY3ODkwMTIzNDU2Nzg5LjEuMS4tMQ",
                "dC4xLjEuLTE"
            })
    void refusesWhatTheListingNeverHandedOut(String cursor) {
        assertRefused(cursor, () -> Cursors.row(cursor));
    }

    /**
     * Values that decode but that the listing never writes: a numbering of 0, a negative one, negative
     * stamps, a padded numbering, a padded stamp, an entry below a trace's own row, a leading space, and
     * base64 with spare bits set.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "dC4wLjEuMS4tMQ",
                "dC4tNy4xLjEuLTE",
                "dC43Li01Li01Li01",
                "dC4wNy4xLjEuLTE",
                "dC43LjAxLjEuLTE",
                "dC43LjEuMS4tMg",
                " dC43LjEuMS4tMQ",
                "dC43LjEuMS4tMR"
            })
    void refusesAPositionTheListingNeverWrites(String cursor) {
        assertRefused(cursor, () -> Cursors.row(cursor));
    }

    /** Another numbering is another store: the restart renumbered every row, so the position names nothing now. */
    @Test
    void refusesARowCursorFromAnotherNumbering() {
        String cursor = Cursors.ofRow(NUMBERING, new RowPosition(1, 1, RowPosition.WHOLE_TRACE));

        assertFromBeforeARestart(cursor, () -> Cursors.row(cursor).under(NUMBERING + 1));
    }

    @Test
    void aLogCursorReadsBackAsTheSequenceItNamed() {
        assertThat(Cursors.ofLog(NUMBERING, 5)).isEqualTo("bC43LjU");
        assertThat(Cursors.log(Cursors.ofLog(NUMBERING, 5)).under(NUMBERING)).isEqualTo(5L);
        assertThat(Cursors.log(" ")).isNull();
    }

    @Test
    void refusesALogCursorFromAnotherNumbering() {
        String cursor = Cursors.ofLog(NUMBERING, 5);

        assertFromBeforeARestart(cursor, () -> Cursors.log(cursor).under(NUMBERING + 1));
    }

    @Test
    void eachEndpointRefusesTheOthersCursor() {
        String rowCursor = Cursors.ofRow(NUMBERING, new RowPosition(1, 1, RowPosition.WHOLE_TRACE));

        assertThatThrownBy(() -> Cursors.log(rowCursor)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> Cursors.row(Cursors.ofLog(NUMBERING, 5))).isInstanceOf(ApiProblemException.class);
    }

    /**
     * A negative sequence, a padded numbering, a negative zero, three numbers, a numbering alone, a
     * numbering of 0, a letter, and a 19-digit sequence past the cap.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "bC43Li0x",
                "bC4wNy41",
                "bC43Li0w",
                "bC43LjUuMQ",
                "bC43",
                "bC4wLjU",
                "bC43LmE",
                "bC43LjEyMzQ1Njc4OTAxMjM0NTY3ODk"
            })
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

    private static void assertFromBeforeARestart(String cursor, ThrowingCallable read) {
        assertThatThrownBy(read).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.problem().status()).isEqualTo(400);
            assertThat(e.problem().parameter()).isEqualTo("cursor");
            assertThat(e.problem().detail())
                    .isEqualTo("Cursor '" + cursor + "' was handed out before the application restarted,"
                            + " which numbered every row and line anew. Read the first page again without a cursor.");
        });
    }
}
