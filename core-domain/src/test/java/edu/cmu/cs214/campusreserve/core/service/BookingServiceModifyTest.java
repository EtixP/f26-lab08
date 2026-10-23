package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingModification;
import edu.cmu.cs214.campusreserve.core.domain.BookingStatus;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.domain.WaitlistEntry;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingServiceModifyTest extends BookingServiceTestBase {

    // --- Clause 1: ownership and timing -------------------------------------

    @Test
    void ownerMovesAnUpcomingBookingWithinTheSameRoom() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
        assertTrue(result.isModified());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(Fixtures.interval("14:00", "15:00"), stored.interval());
        assertEquals("r-1", stored.roomId());
        assertEquals(BookingStatus.CONFIRMED, stored.status());
    }

    @Test
    void ownerShortensABooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 4).isModified());
        assertEquals(60, service.findBooking(id).orElseThrow().durationMinutes());
    }

    @Test
    void ownerLengthensABooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "12:00"), 4).isModified());
        assertEquals(120, service.findBooking(id).orElseThrow().durationMinutes());
    }

    @Test
    void ownerChangesOnlyTheAttendeeCount() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 8).isModified());
        Booking stored = service.findBooking(id).orElseThrow();
        assertEquals(8, stored.attendees());
        assertEquals(Fixtures.interval("10:00", "11:00"), stored.interval());
    }

    @Test
    void rejectsAnUnknownBooking() {
        ModificationResult result =
            service.modifyBooking("bk-missing", "u-1", Fixtures.interval("10:00", "11:00"), 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
    }

    @Test
    void rejectsSomeoneElsesBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();
        ModificationResult result = service.modifyBooking(id, "u-2", Fixtures.interval("14:00", "15:00"), 4);
        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void rejectsACancelledBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.cancelBooking(id, "u-1");
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED), result.rejectionReason());
        assertFalse(service.findBooking(id).orElseThrow().holdsRoom());
    }

    @Test
    void rejectsABookingThatHasStarted() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();
        clock.setTo("2026-10-20T10:00:00Z");
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_STARTED), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    // --- Clause 2: the conflict re-check ------------------------------------

    @Test
    void nudgingByOneSlotIntoItsOwnOldTimeIsAllowed() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
        assertTrue(result.isModified());
        assertEquals(Fixtures.interval("10:15", "11:15"), service.findBooking(id).orElseThrow().interval());
    }

    @Test
    void aMoveIntoAnotherBookingIsRejectedAndTheOriginalStands() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
        Booking before = service.findBooking(id).orElseThrow();
        List<String> eventsBefore = publisher.types();

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:30", "11:30"), 4);

        assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertEquals(2, bookings.count());
        assertEquals(0, waitlist.count());
        assertEquals(eventsBefore, publisher.types());
    }

    @Test
    void aMoveThatOnlyTouchesTheNextBookingIsAllowed() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("11:00", "12:00"), 4).isModified());
    }

    @Test
    void aMoveOntoACancelledBookingsTimeIsAllowed() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        String other = bookAndGetId("r-1", "u-2", Fixtures.interval("14:00", "15:00"));
        service.cancelBooking(other, "u-2");
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4).isModified());
    }

    @Test
    void aMoveOntoTheBookersOwnBookingInAnotherRoomIsRejected() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));
        Booking before = service.findBooking(id).orElseThrow();
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("11:30", "12:30"), 4);
        assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    @Test
    void theBookingCapDoesNotApplyToAModification() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("08:00", "09:00"));
        bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-1", Fixtures.interval("12:00", "13:00"));
        bookAndGetId("r-1", "u-1", Fixtures.interval("14:00", "15:00"));
        bookAndGetId("r-1", "u-1", Fixtures.interval("16:00", "17:00"));
        assertEquals(Optional.of(RejectionReason.USER_BOOKING_LIMIT_REACHED),
            book("r-1", "u-1", Fixtures.interval("18:00", "19:00")).rejectionReason());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("08:15", "09:15"), 4);
        assertTrue(result.isModified());
        assertEquals(5, service.activeBookingsForUser("u-1").size());
    }

    @Test
    void aCollisionNeverPutsTheBookerOnTheWaitlist() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("14:00", "15:00"));
        service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
        assertTrue(service.waitlistForUser("u-1").isEmpty());
    }

    // --- Clause 3: every booking rule still applies -------------------------

    @Test
    void rejectsMoreAttendeesThanTheRoomSeats() {
        assertRejectedAndUnchanged(Fixtures.interval("10:00", "11:00"), 11,
            RejectionReason.ATTENDEES_EXCEED_CAPACITY);
    }

    @Test
    void rejectsAnIntervalInThePast() {
        assertRejectedAndUnchanged(Fixtures.intervalOn("2026-10-19", "07:00", "08:00"), 4,
            RejectionReason.INTERVAL_IN_PAST);
    }

    @Test
    void rejectsAnIntervalShorterThanTheMinimum() {
        assertRejectedAndUnchanged(Fixtures.interval("10:00", "10:15"), 4,
            RejectionReason.INTERVAL_TOO_SHORT);
    }

    @Test
    void rejectsAnIntervalLongerThanTheRoomAllows() {
        assertRejectedAndUnchanged(Fixtures.interval("10:00", "14:00"), 4,
            RejectionReason.INTERVAL_TOO_LONG);
    }

    @Test
    void rejectsAnIntervalOffTheSlotGrid() {
        assertRejectedAndUnchanged(Fixtures.interval("10:10", "11:10"), 4,
            RejectionReason.INTERVAL_NOT_ALIGNED);
    }

    @Test
    void rejectsAnIntervalThatSpansTwoUtcDays() {
        assertRejectedAndUnchanged(TimeInterval.parse("2026-10-20T23:00:00Z", "2026-10-21T00:30:00Z"), 4,
            RejectionReason.INTERVAL_SPANS_DAYS);
    }

    @Test
    void rejectsAnIntervalOutsideOpeningHours() {
        assertRejectedAndUnchanged(Fixtures.interval("21:30", "22:30"), 4,
            RejectionReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void rejectsAnIntervalBeyondTheAdvanceWindow() {
        assertRejectedAndUnchanged(Fixtures.intervalOn("2026-11-10", "10:00", "11:00"), 4,
            RejectionReason.TOO_FAR_IN_ADVANCE);
    }

    @Test
    void rejectsAModificationOnceTheRoomHasLeftService() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        rooms.save(rooms.findById("r-1").orElseThrow().withActive(false));
        Booking before = service.findBooking(id).orElseThrow();
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
        assertEquals(Optional.of(RejectionReason.ROOM_INACTIVE), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
    }

    // --- Clause 4: freed time goes to the waitlist --------------------------

    @Test
    void shorteningPromotesTheWaiterForTheFreedTime() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("11:00", "12:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertEquals(1, result.promotions().size());
        assertEquals("u-2", result.promotions().get(0).booking().userId());
        assertEquals(1, service.activeBookingsForUser("u-2").size());
        assertTrue(service.waitlistForRoom("r-1").isEmpty());
    }

    @Test
    void movingAwayPromotesTheEarliestWaiterFirst() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("10:00", "11:00")).isWaitlisted());
        assertTrue(book("r-1", "u-3", Fixtures.interval("10:00", "11:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);

        assertEquals(1, result.promotions().size());
        assertEquals("u-2", result.promotions().get(0).booking().userId());
        assertEquals(List.of("u-3"),
            service.waitlistForRoom("r-1").stream().map(WaitlistEntry::userId).toList());
    }

    @Test
    void promotionStillRefusesAWaiterWhoWouldBeDoubleBooked() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("11:00", "12:00")).isWaitlisted());
        bookAndGetId("r-2", "u-2", Fixtures.interval("11:00", "12:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertTrue(result.promotions().isEmpty());
        assertEquals(1, service.waitlistForUser("u-2").size());
    }

    @Test
    void lengtheningRunsThePassAndPromotesNobodyWhenNothingFreed() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        assertTrue(book("r-1", "u-3", Fixtures.interval("12:00", "13:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "12:00"), 4);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, service.waitlistForRoom("r-1").size());
    }

    @Test
    void anIntervalChangeWalksTheWaitlistEvenWhenItOnlyLengthens() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        WaitlistEntry stranded = strandedWaiter("u-2", Fixtures.interval("16:00", "17:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:30"), 4);

        assertEquals(List.of(stranded.id()),
            result.promotions().stream().map(promotion -> promotion.entry().id()).toList());
    }

    @Test
    void anAttendeeOnlyChangeStartsNoPromotion() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        strandedWaiter("u-2", Fixtures.interval("16:00", "17:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 6);

        assertTrue(result.isModified());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, service.waitlistForRoom("r-1").size());
        assertTrue(service.activeBookingsForUser("u-2").isEmpty());
    }

    // --- Clause 5: history, and the change that changes nothing -------------

    @Test
    void theBookingKeepsWhatItUsedToBe() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        clock.setTo("2026-10-19T09:30:00Z");
        service.modifyBooking(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
        clock.setTo("2026-10-19T09:45:00Z");
        service.modifyBooking(id, "u-1", Fixtures.interval("10:15", "11:15"), 6);

        List<BookingModification> history = service.findBooking(id).orElseThrow().modifications();
        assertEquals(List.of(
            new BookingModification(Instant.parse("2026-10-19T09:30:00Z"),
                Fixtures.interval("10:00", "11:00"), 4, Fixtures.interval("10:15", "11:15"), 4),
            new BookingModification(Instant.parse("2026-10-19T09:45:00Z"),
                Fixtures.interval("10:15", "11:15"), 4, Fixtures.interval("10:15", "11:15"), 6)),
            history);
    }

    @Test
    void historySurvivesCancellation() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.modifyBooking(id, "u-1", Fixtures.interval("14:00", "15:00"), 4);
        service.cancelBooking(id, "u-1");
        assertEquals(1, service.findBooking(id).orElseThrow().modifications().size());
    }

    @Test
    void aBookingThatWasNeverModifiedHasNoHistory() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(service.findBooking(id).orElseThrow().modifications().isEmpty());
    }

    @Test
    void aModificationThatChangesNothingSucceedsAndRecordsNothing() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        WaitlistEntry stranded = strandedWaiter("u-2", Fixtures.interval("16:00", "17:00"));
        Booking before = service.findBooking(id).orElseThrow();
        List<String> eventsBefore = publisher.types();

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertTrue(result.succeeded());
        assertFalse(result.isModified());
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertTrue(service.findBooking(id).orElseThrow().modifications().isEmpty());
        assertEquals(eventsBefore, publisher.types());
        assertTrue(waitlist.findById(stranded.id()).isPresent());
    }

    @Test
    void aRejectedModificationPublishesNothing() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.modifyBooking(id, "u-2", Fixtures.interval("14:00", "15:00"), 4);
        service.modifyBooking(id, "u-1", Fixtures.interval("10:10", "11:10"), 4);
        assertEquals(List.of("booking-created"), publisher.types());
    }

    /** Books 10:00-11:00 for u-1, asks for a change, and checks it was refused and left nothing behind. */
    private void assertRejectedAndUnchanged(TimeInterval newInterval, int newAttendees, RejectionReason expected) {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = service.findBooking(id).orElseThrow();
        ModificationResult result = service.modifyBooking(id, "u-1", newInterval, newAttendees);
        assertEquals(Optional.of(expected), result.rejectionReason());
        assertEquals(before, service.findBooking(id).orElseThrow());
        assertEquals(0, waitlist.count());
    }

    /**
     * Puts a waiter on r-1 for a slot nobody holds, the way a waiter is left behind
     * when an earlier pass skipped them. Only a promotion pass will move them, so
     * whether they move shows whether a pass ran.
     */
    private WaitlistEntry strandedWaiter(String userId, TimeInterval interval) {
        WaitlistEntry entry = new WaitlistEntry(
            "wl-stranded-" + userId, "r-1", userId, interval, 4, waitlist.nextSequence(),
            Instant.parse(Fixtures.NOW));
        waitlist.save(entry);
        return entry;
    }
}
