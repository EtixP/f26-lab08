package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of a request to modify a booking. A change that went through
 * reports the booking before and after, and the bookings promoted off the
 * waitlist into any time it freed. A request that asked for what the booking
 * already was succeeds as {@link Unchanged} and records nothing.
 */
public sealed interface ModificationResult {

    /** The booking changed. {@code promotions} may be empty. */
    record Modified(Booking previous, Booking booking, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(booking, "booking");
            promotions = List.copyOf(promotions);
        }
    }

    /** The request matched the booking as it stands. Nothing was stored. */
    record Unchanged(Booking booking) implements ModificationResult {
        public Unchanged {
            Objects.requireNonNull(booking, "booking");
        }
    }

    /** The modification was refused. Nothing changed. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the request succeed, whether or not it changed anything? */
    default boolean succeeded() {
        return !(this instanceof Rejected);
    }

    /** Did the booking actually change? */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /** The booking as it stands after the request, when the request succeeded. */
    default Optional<Booking> resultingBooking() {
        return switch (this) {
            case Modified modified -> Optional.of(modified.booking());
            case Unchanged unchanged -> Optional.of(unchanged.booking());
            case Rejected ignored -> Optional.empty();
        };
    }

    /** Promotions triggered by the change, empty unless the interval moved. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
