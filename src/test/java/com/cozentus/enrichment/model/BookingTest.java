package com.cozentus.enrichment.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cozentus.enrichment.JsonSupport;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BookingTest {

    /** The raw booking exactly as SPEC 4 shows it arriving on booking.raw. */
    private static final String RAW = """
            {
              "bookingId": "BKG-12345",
              "shipper": "ABC Logistics",
              "origin": "Mumbi",
              "destination": "now delhi",
              "mode": "ROAD",
              "requestedDate": "2026-09-05"
            }""";

    @Test
    @DisplayName("the SPEC 4 raw payload binds field-for-field")
    void bindsTheSpecPayload() {
        assertThat(JsonSupport.read(RAW, Booking.class))
                .isEqualTo(new Booking("BKG-12345", "ABC Logistics",
                                       "Mumbi", "now delhi", "ROAD", "2026-09-05"));
    }

    @Test
    @DisplayName("a booking survives a JSON round trip unchanged")
    void roundTripsUnchanged() {
        Booking booking = new Booking("BKG-1", "ABC Logistics",
                                      "Mumbai", "Pune", "RAIL", "2026-09-05");

        assertThat(JsonSupport.read(JsonSupport.write(booking), Booking.class))
                .isEqualTo(booking);
    }

    @Test
    @DisplayName("unknown properties are ignored, not fatal")
    void toleratesUnknownProperties() {
        String withExtras = """
                {"bookingId":"BKG-2","shipper":"S","origin":"Mumbai","destination":"Pune",
                 "mode":"AIR","requestedDate":"2026-09-05","priority":"HIGH","weightKg":42}""";

        assertThat(JsonSupport.read(withExtras, Booking.class).bookingId()).isEqualTo("BKG-2");
    }

    @Test
    @DisplayName("absent fields bind to null rather than failing")
    void absentFieldsBindToNull() {
        Booking booking = JsonSupport.read("{\"bookingId\":\"BKG-3\"}", Booking.class);

        assertThat(booking.bookingId()).isEqualTo("BKG-3");
        assertThat(booking.origin()).isNull();
        assertThat(booking.destination()).isNull();
    }

    @Test
    @DisplayName("unparseable input raises, so the processor has something to catch")
    void unparseableInputRaises() {
        assertThatThrownBy(() -> JsonSupport.read("{not json", Booking.class))
                .isInstanceOf(UncheckedIOException.class);
    }
}
