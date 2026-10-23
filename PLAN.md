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

**What happened against the takeover point:** no trigger fired, so I did not take
over. I ran all three checks after the run:

| Trigger | Result |
|---|---|
| Scope | `git diff --stat` lists only `core-domain/src/**` for code. |
| Shipped tests | Every `*/src/test/**` path in the diff was checked with `git cat-file -e 3a9923d:<path>`. None existed in `3a9923d`. |
| Rule-3 grep | Printed nothing (exit 1). |
| Gate | Green on the first run. |

**The evidence.** The task is T1, "a booker can modify an upcoming booking they
own" (core-domain). Full logs are in `evidence/m3-t1-gate.log` and
`evidence/m3-closing-clean-build.log`.

- Gate, `mvn -B -pl core-domain -am test` (core-domain block, was 167):
  ```
  [INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.030 s -- in edu.cmu.cs214.campusreserve.core.service.BookingServiceModifyTest
  [INFO] Tests run: 202, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  ```
- Closing `mvn -B clean test`, cold, run with the lock still on. Every module ran,
  and the Java total is 453 = 418 + 35:
  ```
  [INFO] Tests run: 202, Failures: 0, Errors: 0, Skipped: 0     (core-domain)
  [INFO] Tests run: 63, Failures: 0, Errors: 0, Skipped: 0      (storage)
  [INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0      (notifications)
  [INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0      (accounts)
  [INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0      (billing)
  [INFO] Tests run: 78, Failures: 0, Errors: 0, Skipped: 0      (http-api)
  [INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0      (cli)
  [INFO] BUILD SUCCESS
  ```

**The diff.** `git add -N . && git diff --stat -- core-domain`:

```
 .../cs214/campusreserve/core/domain/Booking.java   |  38 +-
 .../core/domain/BookingModification.java           |  41 +++
 .../core/domain/ModificationResult.java            |  73 ++++
 .../campusreserve/core/service/BookingService.java |  78 ++++-
 .../core/service/BookingServiceModifyTest.java     | 385 +++++++++++++++++++++
 5 files changed, 610 insertions(+), 5 deletions(-)
```

| File | Maps to T1's declared scope |
|---|---|
| `core-domain/.../domain/Booking.java` | CR 5, the history representation decided in M1. Adds the `modifications` component, a 7-argument constructor so existing callers compile unchanged, `cancelled()` keeping the history, and `modified(...)`, which appends one entry. |
| `core-domain/.../domain/BookingModification.java` (new) | CR 5: "what changed and when". Holds `modifiedAt` plus the previous and new interval and attendee count. |
| `core-domain/.../domain/ModificationResult.java` (new) | The error surface decided in M1: `Modified` / `Unchanged` / `Rejected`, mirroring `CancellationResult`. It reuses `CancellationResult.Promotion` and adds no new `RejectionReason`. |
| `core-domain/.../service/BookingService.java` | `modifyBooking`, CR 1–4, plus the private helper `withoutBooking`. It adds no `waitlist.save`/`remove` call and only reuses `promoteWaitlist`. |
| `core-domain/.../service/BookingServiceModifyTest.java` (new) | 35 tests covering checkpoint items (a)–(j). It is a new file, not a shipped test. |

Things outside the T1 code scope also appear in the full diff, all expected:

- `PLAN.md`
- `evidence/*`
- `guardrails/protected-trees.txt` (the Milestone 2 configuration)
- `README.md` (the tool line)
- `lab08.md` (the handout, untracked; not mine)

Nothing was touched in `storage`, `notifications`, `http-api`, `analytics`,
any `pom.xml`, or any shipped test.

**The line I read hardest:** `BookingService.java:209`:

```java
List<Booking> roomOthers = withoutBooking(bookings.findActiveByRoom(booking.roomId()), bookingId);
```

This is CR 2's "the check ignores the booking being modified". I checked four
things:

1. The exclusion is by **booking id**. If it filtered by user, it would also drop
   the booker's *other* bookings in this room. If it filtered by interval
   equality, the nudge would still work but the key would be the wrong one.
2. The room comes from the **stored** booking, never from the caller, so "the room
   stays the same" holds by construction.
3. The filtered list goes into `conflictChecker.hasConflict(roomOthers, newInterval)`
   on line 210. That is the rule-3 path through `TimeInterval.overlaps`, not a
   hand comparison of starts and ends.
4. Line 205 does the same for the booker's own calendar, and nowhere near either
   check is there a `maxActiveBookingsPerUser` comparison. The cap stays waived.

**The refuter.** The refuter ran after the closing build, in a fresh Claude Code
subagent session that shared no context with the run. It was given exactly
three files, copied to a separate folder:

1. `CHANGE-REQUEST.md`
2. the task's diff, `git add -N . && git diff -- core-domain`
3. T1's one-line scope from this plan

It was told not to open `PLAN.md`, `MILESTONE.md`, or `evidence/`. The brief,
verbatim, is in `evidence/m3-refuter-brief.md`. Its core:

> You are a **refuter**. Another agent has just implemented one task from a
> change request … and that task's test gate is green. Your job is to **break
> it**: find concrete inputs or call sequences where the task does the wrong
> thing according to the change request. … Judge the task only against its
> one-line scope. … Do **not** edit production code or any existing test. You
> may add probe tests only as **new** files named `…/RefuterProbe*Test.java`. …
> Start where the gate is blind. … Angles worth trying: how "the booking being
> modified" is excluded from the room and own-calendar checks; whether a
> rejected modification truly changes nothing …; the no-op rule; which
> rejection wins …; when the promotion pass does and does not run …; what the
> history records; the "upcoming" boundary; whether the change to the `Booking`
> record breaks anything. … Report each finding with the claim, the clause it
> violates (quoted), the probe and its actual result, and your confidence. Say
> plainly when a suspicion did **not** reproduce.

**What it reported:** it wrote 19 probes in `RefuterProbeModifyTest.java`, and 17
were green. Those covered the self-exclusion by id, the waived cap, a rejection
changing nothing, the no-op, rejection precedence, the one-second-before-start
boundary, first-in-first-out promotion with no mutual conflict afterwards, and
history fields. Two were red, both rated low confidence:

1. A lengthen can promote a waiter.
2. `attendees = 0` throws `IllegalArgumentException` instead of returning
   `Rejected`.

**The finding I settled: #1, the strongest. I dismissed it with evidence.**

*Claim.* The refuter reached this state through the public API only:

1. u-2 joins the r-1 waitlist for 14:00–15:00.
2. u-2 books r-2 at 14:00–15:00.
3. u-3 cancels the r-1 booking at 14:00–15:00. u-2 is skipped, since they hold
   r-2 at that time.
4. u-2 cancels r-2. That pass only walks r-2, so u-2 is left stranded on the r-1
   list with a free slot.

Then u-1 lengthens 10:00–11:00 to 10:00–11:30 in r-1, and the pass promotes
u-2. The refuter cites CR 4: "A modification that only lengthens a booking frees
nothing either. Running the pass and promoting nobody is the right outcome
there."

*Checking it.* First I re-ran its probe myself. It reproduced, red
(`evidence/m3-refuter-probes-run.log`):

```
RefuterProbeModifyTest.lengthenPromotesAStrandedWaiterReachedThroughThePublicApi:196 lengthen promoted [Promotion[entry=WaitlistEntry[id=wl-2, roomId=r-1, userId=u-2, interval=[2026-10-20T14:00:00Z, 2026-10-20T15:00:00Z) ...]] ==> expected: <true> but was: <false>
Tests run: 19, Failures: 2, Errors: 0, Skipped: 0
```

*Why it is dismissed.* The behavior is the one CR 4 orders, applied to a state
that predates the modification. CR 4 says modification "triggers **the same**
first in, first out waitlist promotion that a cancellation triggers today", and
that "**The promotion pass runs when a successful modification changed the
interval.**" "Running the pass and promoting nobody" describes the outcome when
nothing was free before. It is not an instruction to filter the pass.

I tested that reading with `SettleFinding1Test`, a temporary test with three
cases built on the refuter's exact stranded state. Its log is
`evidence/m3-finding1-settle.log` and its source is
`evidence/SettleFinding1Test.java.txt`:

- `theShippedCancellationPassPromotesTheStrandedWaiterFromUnrelatedFreedTime`:
  starter code only (`requestBooking`/`cancelBooking`). Cancelling u-1's
  10:00–11:00 booking promotes the stranded u-2 into **14:00–15:00**, time that
  the cancellation did not free. The shipped pass already behaves this way.
- `lengtheningPromotesExactlyWhatThatCancellationWould`: the lengthen promotes
  the identical waiter and interval, so it is "the same" pass.
- `withNothingStrandedALengthenPromotesNobody`: with no pre-existing stranded
  waiter, a lengthen promotes nobody, which is CR 4's stated outcome.

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in edu.cmu.cs214.campusreserve.core.service.SettleFinding1Test
[INFO] BUILD SUCCESS
```

The T1 diff does not touch `promoteWaitlist`; it only adds one call to it
(`git diff … BookingService.java | grep promoteWaitlist` shows the single `+`
call line). Making the lengthen promote nobody here would mean either forking a
second, "freed-time-only" pass, contrary to "the same", or changing the shipped
cancellation behavior. Both are outside T1's scope. Stranding itself comes from
the cancellation path (a cancel in r-2 does not walk r-1), which is pre-existing
behavior.

*Finding 2, not settled, noted for T4.* `modifyBooking` with `attendees ≤ 0`
throws, through `BookingRequest.of`. This is the same contract as
`requestBooking`, whose `BookingRequest` constructor throws the same exception.
T4's checkpoint gains one item: `PATCH` with `attendees=0` returns 400, validated
in the handler before it calls the service, the way `POST` does.

*Clean-up.* I deleted both probe files, `RefuterProbeModifyTest.java` and
`SettleFinding1Test.java`. Copies are kept as `.txt` in `evidence/`. Afterwards,
`git diff --stat -- core-domain` again shows exactly the 5 T1 files.
`guardrails/unlock.sh` was run, and a final `mvn -B clean test` (453 tests,
BUILD SUCCESS) and `pytest` (137 passed) confirm the tree as handed in.
