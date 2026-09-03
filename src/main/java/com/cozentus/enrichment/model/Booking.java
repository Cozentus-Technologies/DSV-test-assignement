package com.cozentus.enrichment.model;

/** A booking exactly as it arrives on {@code booking.raw} (SPEC 4). */
public record Booking(String bookingId,
                      String shipper,
                      String origin,
                      String destination,
                      String mode,
                      String requestedDate) {
}
