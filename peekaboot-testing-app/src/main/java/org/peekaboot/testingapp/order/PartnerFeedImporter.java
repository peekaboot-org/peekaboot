package org.peekaboot.testingapp.order;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Imports a partner's order feed that fails the same two ways on every call, so one request's
 * trace carries more than one throwable: a row with no quantity, skipped and logged with the
 * parse failure as its cause, and a read that breaks off mid-record whose feed then fails to
 * close, which try-with-resources attaches as a suppressed exception. Both are caught and
 * logged; the import itself answers normally.
 */
@Service
public class PartnerFeedImporter {

    private static final String REJECTED_ROW = "PK-FEED-1;two";

    private static final Logger log = LoggerFactory.getLogger(PartnerFeedImporter.class);

    public String importFeed() {

        try {
            quantityOf(REJECTED_ROW);
        } catch (IllegalArgumentException e) {
            log.warn("skipped partner feed row {}", REJECTED_ROW, e);
        }
        try (PartnerFeed feed = new PartnerFeed()) {
            while (feed.hasNext()) {
                feed.next();
            }
        } catch (IllegalStateException e) {
            log.error("partner feed import broke off", e);
        }
        return "partner feed imported with errors";
    }

    private static int quantityOf(String row) {

        String quantity = row.substring(row.indexOf(';') + 1);
        try {
            return Integer.parseInt(quantity);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("row " + row + " carries no quantity", e);
        }
    }

    private static final class PartnerFeed implements AutoCloseable {

        private final Iterator<String> records =
                List.of("PK-FEED-2;1", "PK-FEED-3").iterator();

        boolean hasNext() {

            return records.hasNext();
        }

        String next() {

            String record = records.next();
            if (record.indexOf(';') < 0) {
                throw new IllegalStateException("partner feed ended mid-record at " + record);
            }
            return record;
        }

        @Override
        public void close() {

            throw new UncheckedIOException(new IOException("partner feed connection reset"));
        }
    }
}
