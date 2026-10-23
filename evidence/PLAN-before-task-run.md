# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

*Finished 2026-10-23 at 11:13 EDT. That was after the baseline run (418 Java and 137
Python tests green) and before any task ran or any source file was edited.
`evidence/timeline.txt` has the timestamps.*

**Decisions the change request leaves open (settled here, before any run):**

- **History representation.** The history lives on the booking itself. `Booking`
  gains a `modifications` component, a list of a new
  `BookingModification(modifiedAt, previousInterval, previousAttendees, interval, attendees)`.
  An extra 7-argument constructor defaults it to empty, so every existing
  `Booking` construction compiles unchanged. `cancelled()` carries the history
  along. The record lives in core-domain, so storage needs no new store, and
  `InMemoryBookingStore.save` already replaces by id.
- **Error surface.** No new rejection codes. Clause 1 reuses `NOT_BOOKING_OWNER`,
  `BOOKING_ALREADY_CANCELLED`, `BOOKING_ALREADY_STARTED`, and `BOOKING_NOT_FOUND`.
  Clause 3 reuses the rule reasons `BookingRules.checkRequest` already returns.
  Clause 2 reuses `ROOM_UNAVAILABLE` (another booking holds the room) and
  `USER_DOUBLE_BOOKED` (the booker's own calendar). `ErrorMapping` already maps
  all of these to HTTP statuses.
- **No-op.** The clause-1 checks (owner, not cancelled, not started) run first.
  Then a request whose interval and attendee count both equal the stored ones
  returns `Unchanged`. It runs no rules, stores nothing, publishes nothing, and
  runs no promotion pass (CR 5: "succeeds and records nothing at all").
- **Collision ≠ waitlist.** A modification that collides is rejected. It never
  joins the waitlist the way `requestBooking` does (CR 2: "changes nothing at
  all").

**The tasks** (dependency order):

**T1. A booker can modify an upcoming booking they own (domain behavior, CR 1–5).**
- *Delivers:* `BookingService.modifyBooking(bookingId, requesterId, newInterval, newAttendees)`
  returns a new sealed `ModificationResult` (`Modified` / `Unchanged` /
  `Rejected`). The steps run in this order:
  1. Clause 1: ownership, not cancelled, not started.
  2. No-op short-circuit.
  3. Clause 3: `BookingRules.checkRequest` on the new interval and attendee count.
     That is the whole rule list and nothing more.
  4. Clause 2: the booker's own calendar, then the room. Both go through
     `ConflictChecker` over the active bookings with this booking's **id**
     removed. The cap is not consulted.
  5. Save the modified booking with a new history entry.
  6. Clause 4: if the interval changed, run the existing private
     `promoteWaitlist(roomId)` pass. Attendee-only changes skip it.
- *Modules:* `core-domain` only: `domain/Booking.java`,
  new `domain/BookingModification.java`, new `domain/ModificationResult.java`,
  `service/BookingService.java`, and the new test
  `src/test/.../service/BookingServiceModifyTest.java`. It publishes no event yet
  (that is T2). *Depends on:* nothing.
- *Checkpoint:* `mvn -B -pl core-domain -am test`. To pass, the core-domain line
  reads `Tests run: 167+N, Failures: 0, Errors: 0` with N ≥ 15 and BUILD SUCCESS,
  and the new suite has a named test for each of these:
  - (a) a nudge by one 15-minute slot that overlaps the booking's own old time
    succeeds
  - (b) a move into another booking's time is rejected `ROOM_UNAVAILABLE`, after
    which `findById` equals the original, the waitlist is empty, and nothing was
    saved
  - (c) a booker holding 5/5 bookings can still modify
  - (d) an overlap with the booker's own booking in another room is rejected
    `USER_DOUBLE_BOOKED`
  - (e) every clause-3 rule rejects (capacity, past, too long, misaligned, opening
    hours, spans days, too far ahead, room inactive)
  - (f) started, cancelled, and non-owner bookings are rejected
  - (g) shortening promotes the earliest-arriving waiter for the freed time
  - (h) an attendee-only change promotes nobody, even when a promotable waiter
    exists
  - (i) a no-op leaves no history entry
  - (j) the history entry carries the old and new interval and attendee count

  `git add -N . && git diff --stat` must list only `core-domain/src/**` and no
  shipped test file.
- *Green but wrong:* the agent removes "the booking being modified" by
  hand-rolling an overlap test, such as
  `other.start().isBefore(newEnd) && newStart.isBefore(other.end())` in
  `BookingService`. It computes the same answer as `TimeInterval.overlaps` on
  every fixture, so all tests pass, but it is the second conflict predicate that
  AGENTS.md rule 3 forbids. Only the diff audit below catches it.

**T2. The owner is told about the change, and the log records `booking-modified` (CR 6, CR 8 Java side).**
- *Delivers:*
  - **core-domain:** new `events/BookingModifiedEvent`. Its core
    `interval`/`attendees` are the **new** values, plus `previousInterval` and
    `previousAttendees`. It is added to `DomainEvent`'s `permits`, and
    `modifyBooking` publishes it only on a real change (CR 5: a no-op publishes
    nothing).
  - **storage:** `EventLogFormat.toLine` appends `previousStart`, `previousEnd`,
    and `previousAttendees` for this type, and `eventTypes()` lists it.
  - **notifications:** `NotificationKind.BOOKING_MODIFIED("booking-modified")`, a
    template that names the old and new time and attendee count, and the
    `forEvent`/`valuesFor` cases. The existing promotion notice is unchanged
    (CR 6).
- *Modules:* `core-domain` (events, one publish call), `storage`, `notifications`.
  *Depends on:* T1.
- *Checkpoint:* `mvn -B -pl storage,notifications -am test`. The core-domain,
  storage, and notifications blocks must all show `Failures: 0, Errors: 0`, with
  storage above 63 and notifications above 41. One new storage test must assert
  the exact `booking-modified` line, and one new notifications test must assert
  the queued body contains both `IntervalText.formatUtc(old)` and
  `IntervalText.formatUtc(new)`. Then `mvn -B clean test`, because the sealed
  `permits` change ripples into every exhaustive switch.
- *Green but wrong:* the agent builds the event from the pre-change booking, so
  the core `start`/`end` carry the **old** interval. The storage test is written
  from that same event and passes. The analytics fold (T3) would then count the
  old length forever.

**T3. The utilization report counts a modified booking at its current length (CR 8, Python side).**
- *Delivers:* `analytics/campusreserve_analytics/events.py` learns
  `BOOKING_MODIFIED`. It goes in `KNOWN_EVENT_TYPES` but **not** in
  `HOLDING_EVENT_TYPES`. `Event` parses the optional `previous*` fields, and
  `build_ledger` updates the existing `BookingRecord`'s `start`/`end`/`attendees`
  in place, keeping `created_at`, `from_waitlist`, and `cancelled_at`. A
  modification for a booking the log never opened is collected rather than
  dropped. `utilization.py` needs no change. `revenue.py` is **not touched**,
  because both reports read the same fold.
- *Modules:* `analytics` (events.py and new tests). *Depends on:* T2's line shape,
  which is the contract fixed above.
- *Checkpoint:* `cd analytics && ../.venv/bin/pytest -v` reports `137+N passed`,
  and the new tests cover:
  - booking 10:00–12:00, modified to 10:00–11:00, gives that room
    `booked_minutes == 60` and `booking_count == 1`
  - a promoted booking that is later modified still counts in `promoted_count`
  - the revenue report over the same log still includes the booking (not
    filtered)
  - the fixture line is copied verbatim from T2's storage test, not retyped
- *Green but wrong:* the agent adds `booking-modified` to `HOLDING_EVENT_TYPES`.
  Because the ledger is a dict keyed by booking id, a new record overwrites the
  old one, so the minutes test passes (60). But the rebuilt record loses
  `from_waitlist` and `created_at`, and a promoted booking silently stops
  counting as a promotion.

**T4. `PATCH /bookings/{bookingId}` and history on the existing read surface (CR 5, CR 7, CR 9).**
- *Delivers:*
  - `Router.patch(...)`.
  - `BookingsHandler` registers `PATCH /bookings/{bookingId}`, **owner only**
    (an administrator is refused, unlike GET). Its params are `start` plus
    `end`/`minutes`, and/or `attendees`; a missing one keeps the current value.
    Rejections go through `ErrorMapping`.
  - `JsonViews.booking` gains a `modifications` array, so `GET /bookings/{id}`
    shows what the booking used to be, with no new endpoint.
  - Docs: the event-type list in `AGENTS.md` rule 6, and the event-type list
    and HTTP table in `ARCHITECTURE.md` (CR 9). I make those three edits myself,
    because the guardrail fences `AGENTS.md`.
- *Modules:* `http-api`, plus the two docs files. *Depends on:* T1 (and T2 for
  the event to reach the log).
- *Checkpoint:* `mvn -B -pl http-api -am test` shows the http-api line at
  `Tests run: 78+N, Failures: 0, Errors: 0`. New tests cover:
  - owner PATCH returns 200 with the new times
  - another member gets 403
  - **an administrator gets 403**
  - PATCH into a taken slot returns 409, and a following GET returns the
    original times
  - GET after a success lists one `modifications` entry with old and new times

  `grep -n "booking-modified\|PATCH" AGENTS.md ARCHITECTURE.md` shows all three
  statements updated. Then `mvn -B clean test`.
- *Green but wrong:* PATCH reuses GET's owner-or-administrator check, so an
  administrator can rewrite anyone's booking. Tests that only try the owner and
  a stranger member stay green.

**AGENTS.md rules 3 and 4.** T1's checkpoint catches these, through its
diff-audit step rather than its test count. A second overlap predicate that
agrees with `TimeInterval.overlaps` passes every test. The audit has two greps.
`git diff -U0 -- '*/src/main/**' | grep -nE '^\+.*\.(isBefore|isAfter|compareTo)\('`
must show nothing that compares two intervals' starts and ends outside
`TimeInterval.java` (rule 3).
`grep -rnE 'waitlist\.(save|remove)|WaitlistStore' --include='*.java' */src/main`
must show writes only in `BookingService.java` (rule 4). T1 is the only task
that writes conflict or promotion logic. T4's checkpoint reruns both greps,
because a handler is the next most likely place to reach for a store.

**Out of scope, with reasons.**

| Not touched | Reason |
|---|---|
| `billing/` | CR, out of scope: "The price does not change … Billing is not part of this change request. Do not re-price, re-issue, or void and re-invoice." |
| `cli/` | CR: "Nothing in the CLI. The administrative commands render fields that already exist and do not need a new command." |
| `accounts/` | CR: "Nothing in account management. Modification uses the token and ownership checks that already exist. Accounts does not change." |
| `analytics/campusreserve_analytics/revenue.py` (a file inside an in-scope package) | CR: "The revenue report is not part of this change … Do not filter modified bookings out of it … do not fork the reading." |
| every `pom.xml` (dependency edges) | AGENTS.md rule 1 (no new edges without editing the list first; none is needed) and rule 6 (no JSON library). |
| shipped tests in every module | AGENTS.md rule 7: add new tests, never edit a shipped one. |
| `CHANGE-REQUEST.md`, `CLAUDE.md` | They are the spec and the rules the work is judged against. `AGENTS.md` changes only for the CR-9 event list, edited by me in T4. |

---

## Milestone 2: The guardrail

*Configured and triggered at 11:14 EDT, before any task ran (`evidence/timeline.txt`).*

**The recipe.** Recipe C, the filesystem lockdown. I picked it because the agent
(Claude Code in the VS Code extension) was running in auto mode, where it
writes through Bash as readily as through Edit. Recipes A and B never see a
Bash write, but the OS refuses both. `guardrails/protected-trees.txt` lists the
out-of-scope modules from Milestone 1:

```
billing
cli
accounts
```

`guardrails/lockdown.sh` made `billing/src`, `billing/pom.xml`, `cli/src`,
`cli/pom.xml`, `accounts/src`, and `accounts/pom.xml` read-only. Each module's
`target/` stays writable, so every gate runs.

**What it cannot catch:** anything inside the trees I left open. That covers a
wrong-but-compiling change in `core-domain`, an edit to
`analytics/campusreserve_analytics/revenue.py` (Python is not lockable by this
recipe), and an edit to `AGENTS.md`/`CHANGE-REQUEST.md`. It also cannot stop an
agent that runs `chmod -R u+w billing/src` first, and that leaves no trace in
`git diff`. Only the diff audit covers those.

**The captured block.** These are the agent's own turn and tool calls,
verbatim. The full excerpt is in `evidence/m2-guardrail-capture.md`.

> **Instruction (deliberately out of bounds, per `guardrails/README.md` step 3):**
> "Use your Edit tool to add a comment at the top of
> `billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java`."
>
> **Agent tool call: `Edit`**
> `file_path: .../f26-lab08/billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java`
> `old_string: "package edu.cmu.cs214.campusreserve.billing;\n\nimport java.util.Objects;"`
> `new_string: "// Guardrail probe: this comment should never land (billing is out of scope).\npackage ..."`
>
> **Returned:**
> `EACCES: permission denied, open '/Users/jsp2022310/.../billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java.tmp.86281.764e9a488284'`
>
> **Agent tool call: `Bash`** (the route auto mode takes, which recipes A and B cannot see)
> `sed -i '' '1i\ // Guardrail probe via shell...' cli/src/main/java/.../cli/CampusReserveCli.java; echo "// probe" >> accounts/pom.xml; touch billing/src/main/java/.../billing/Probe.java`
>
> **Returned:**
> ```
> sed: cli/src/main/java/edu/cmu/cs214/campusreserve/cli/CampusReserveCli.java: Permission denied
> sed exit 1
> (eval):3: permission denied: accounts/pom.xml
> append exit 1
> touch: billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Probe.java: Permission denied
> touch exit 1
> ```
>
> **Files unchanged:** `shasum -c` against checksums taken just before the
> attempt printed `Money.java: OK` and `CampusReserveCli.java: OK`.
> `git diff --stat -- billing cli accounts` is empty, and no `Probe.java` exists.

The guide asks for Manual mode so that auto mode's Bash edits do not slip past
recipes A and B. Recipe C is enforced by the OS, so the mode does not change
the outcome. The capture above includes both the Edit tool and the Bash route.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**Task run:** T1, "a booker can modify an upcoming booking they own"
(core-domain). It depends on nothing.

**The takeover point.** *Written at 11:15 EDT, before the first T1 edit.* I stop
the agent and take over by hand on the **first** of these triggers:

1. **Scope breach.** `git add -N . && git diff --stat` lists a path outside
   `core-domain/src/` (other than `PLAN.md`, `MILESTONE.md`, `README.md`,
   `evidence/`). That includes any `pom.xml` and any shipped test, meaning a
   file under `*/src/test/` that exists in commit `3a9923d`.
   *Action:* `git checkout 3a9923d -- <path>`, then I finish T1 myself from the
   last in-scope state.
2. **Second overlap predicate (rule 3).** This command prints any line:
   `git diff -U0 -- '*/src/main/**' | grep -nE '^\+.*\.(isBefore|isAfter|compareTo)\('`.
   The exception is a call to the existing `Booking.hasStartedBy`/`BookingRules`
   helpers, which compare a single instant to now rather than two intervals.
   *Action:* I delete the hand-rolled comparison and write the check myself:
   `conflictChecker.hasConflict(others, newInterval)`, where `others` drops the
   booking by `id`.
3. **Stuck gate.** The T1 gate (`mvn -B -pl core-domain -am test`) is still red
   after the agent's second attempt at the same failing test, or the agent
   proposes editing a shipped test or `core/support/*` fixture to get green.
   *Action:* stop, read the first failing assertion myself, and fix the
   production code by hand.

**The evidence.** Name the task from Milestone 1. Paste the result line of its
gate command (run it without `-q` so the line is in the output) and the result
line of one `mvn -B clean test` run after the task was finished. The module
gate cannot see a cross-module compile break on a warm tree. A cold build can,
and so can CI.

**The diff.** The files the agent changed, and how each one maps to that task's
declared scope (plain `git diff` leaves out new files, but
`git add -N . && git diff --stat` includes them). Note anything it touched that
you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

**The refuter.** What you gave it and what you asked it to do (paste the
brief). Then the one finding you settled, and how (the new failing test with
its red run, or the evidence that dismissed it). If nothing it reported held
up, say how you checked its strongest claim.
