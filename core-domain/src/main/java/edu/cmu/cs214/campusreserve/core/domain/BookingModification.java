package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One change to a booking: what it was, what it became, and when.
 *
 * <p>A booking keeps these in order as its modification history. The room and
 * the booker never change, so only the interval and the attendee count are
 * recorded.
 */
public record BookingModification(
    Instant modifiedAt,
    TimeInterval previousInterval,
    int previousAttendees,
    TimeInterval interval,
    int attendees) {

    public BookingModification {
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Objects.requireNonNull(previousInterval, "previousInterval");
        Objects.requireNonNull(interval, "interval");
        if (previousAttendees <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + previousAttendees);
        }
        if (attendees <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + attendees);
        }
    }

    /** Did the change move or resize the interval? */
    public boolean intervalChanged() {
        return !previousInterval.equals(interval);
    }

    /** Did the change alter the attendee count? */
    public boolean attendeesChanged() {
        return previousAttendees != attendees;
    }
}
