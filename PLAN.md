# PLAN.md

> **A note on timing.** Everything in Milestone 1 was decided and written at
> 11:13 EDT on Oct 23, before any code changed. Later that day I reworded this
> whole file so it's easier to read out loud. The facts didn't change, only the
> wording. The original wording is saved in `evidence/PLAN-after-milestone1.md`,
> and it's also in the first commit, `b5eb4ad`.

---

## Milestone 1: The plan

### The short version

The change request boils down to: "let people edit a booking they already
have." I split it into four tasks, done in this order:

1. **T1.** The core rules for editing a booking (core-domain).
2. **T2.** Tell the owner about the change, and write a new event to the log
   (core-domain events, storage, notifications).
3. **T3.** Teach the Python utilization report about the new event
   (analytics).
4. **T4.** Add the HTTP endpoint, show the booking's history, and fix the docs
   (http-api, AGENTS.md, ARCHITECTURE.md).

For each task I say what it does, where the code goes, what it needs first, how
I'll know it's done, and one way it could pass its tests and still be wrong.

### Four calls I made up front

The change request leaves these open, so I decided them before running anything.

- **Where the history lives.** On the booking itself. Every booking gets a list
  of its past changes. Each entry says when the change happened, what the time
  and headcount were before, and what they became. I also add a second
  constructor, so all the existing code that builds a booking still compiles
  without being touched.
- **Error messages.** No new error codes. Every refusal reuses one that already
  exists, like "not your booking," "already started," "room is taken," or "you're
  already booked then." The HTTP layer already knows how to turn those into
  status codes.
- **A "change" that changes nothing.** If you ask to change a booking to exactly
  what it already is, that succeeds and does nothing. No history entry, no
  notification, no event. The "is it yours, and has it started" checks still run
  first.
- **A clash doesn't put you on the waitlist.** If the new time is taken, the
  edit is simply refused and the booking stays as it was. That's different from
  a brand-new booking, which would put you on the waitlist.

### T1: A booker can edit an upcoming booking they own

**What it does.** It adds a `modifyBooking` method to `BookingService`. You give
it the booking, who's asking, the new time, and the new headcount. It checks
things in this order:

1. Is it your booking, still active, and not started yet? (Clause 1.)
2. Is this actually a change? If not, it succeeds and stops here.
3. Do the new time and headcount pass every normal booking rule? (Clause 3.)
   That's minimum and maximum length, 15-minute slots, opening hours, staying in
   one UTC day, room capacity, how far ahead, not in the past, and the room
   still being in service.
4. Does the new time clash with your own other bookings, or with anyone else's
   booking in this room? Both checks ignore the booking being edited, so it never
   clashes with itself. The "five bookings max" cap is not checked. (Clause 2.)
5. Save it, with a new history entry. (Clause 5.)
6. If the time changed, run the same waitlist promotion that a cancellation
   runs. If only the headcount changed, skip it. (Clause 4.)

It returns one of three results: *Modified*, *Unchanged*, or *Rejected* with a
reason.

**Where.** core-domain only. That's `Booking.java`, two new files
(`BookingModification.java` and `ModificationResult.java`), `BookingService.java`,
and a new test file, `BookingServiceModifyTest.java`. No event yet. That's T2.

**Needs first.** Nothing.

**How I'll know it's done.** I run this:

```
mvn -B -pl core-domain -am test
```

The core-domain line should go from 167 tests to at least 182, with zero
failures and BUILD SUCCESS. The new test file also has to have a test for each of
these:

- **(a)** Sliding a booking 15 minutes, so it overlaps its own old time, is
  allowed.
- **(b)** Moving into someone else's time is refused as "room unavailable." After
  that, the booking is exactly as it was, the waitlist is empty, and nothing was
  saved.
- **(c)** Someone already holding 5 out of 5 bookings can still edit one.
- **(d)** Clashing with your own booking in another room is refused as "you're
  double-booked."
- **(e)** Each normal booking rule refuses a bad edit.
- **(f)** Started bookings, cancelled bookings, and other people's bookings are
  all refused.
- **(g)** Shortening a booking gives the freed time to the first person waiting.
- **(h)** Changing only the headcount promotes nobody.
- **(i)** A change that changes nothing leaves no history.
- **(j)** The history entry has the old and new time and headcount.

I also check that the diff only touches `core-domain/src` and doesn't edit any
test that shipped with the starter.

**How it could fool me.** The agent could write its own "do these two time
ranges overlap?" check, comparing starts and ends by hand, instead of using the
one the repo already has (`TimeInterval.overlaps`). It would give the same
answers, so every test would pass. But AGENTS.md rule 3 says there's only ever
one overlap check in the whole codebase. Tests can't see this. Only reading the
diff can, and that's covered in the "rules 3 and 4" section below.

### T2: The owner gets told, and the log records the change

**What it does.**

- **core-domain:** a new `BookingModifiedEvent`. It carries the new time and
  headcount, and the old ones too. `modifyBooking` sends it only when something
  really changed. (Clauses 5 and 8.)
- **storage:** the event log writes a `booking-modified` line. It has three extra
  fields: `previousStart`, `previousEnd`, and `previousAttendees`.
- **notifications:** a new message that tells the owner the old details and the
  new ones. (Clause 6.) The existing "you got off the waitlist" message stays as
  it is.

**Where.** core-domain (the event, plus one line in the service), storage, and
notifications.

**Needs first.** T1.

**Why this isn't part of T1.** The list of event types in core-domain is
*sealed*, and notifications has switch statements that have to cover every
type. If I add an event in core-domain alone, core-domain's tests stay green,
but notifications stops compiling. So the event, its log line, and its message
all ship together here.

**How I'll know it's done.**

```
mvn -B -pl storage,notifications -am test
```

core-domain, storage, and notifications all show zero failures. Storage goes
above 63 tests, and notifications goes above 41. One new storage test checks the
exact log line. One new notifications test checks that the message mentions both
the old time and the new time. Then I do a full `mvn -B clean test`, because a
new event type touches every switch that covers all the events.

**How it could fool me.** The agent builds the event from the booking as it was
*before* the change. Then the log line's main start and end are the old times.
The storage test gets written from that same wrong event, so it passes. But the
report in T3 would count the old length forever.

### T3: The utilization report counts an edited booking at its current length

**What it does.** The Python reader
(`analytics/campusreserve_analytics/events.py`) learns the `booking-modified`
event. When it sees one, it updates that booking's start, end, and headcount in
place. It keeps everything else it knows about the booking: when it was
created, whether it came off the waitlist, and whether it was cancelled. A change
for a booking it has never seen gets set aside, not thrown away.

The utilization report itself needs no change. The revenue report isn't touched
at all, because both reports read the same folded-up data.

**Where.** analytics: `events.py` and new tests.

**Needs first.** T2's log-line format, which I already fixed above.

**How I'll know it's done.**

```
cd analytics && ../.venv/bin/pytest -v
```

It goes from 137 passed to more, and the new tests show:

- A booking from 10 to 12, changed to 10 to 11, counts as 60 minutes, and as one
  booking, not two.
- A booking that came off the waitlist and was then edited still counts as a
  waitlist promotion.
- The revenue report still includes the edited booking.
- The test's sample log line is copied exactly from T2's storage test, not typed
  in again by hand.

**How it could fool me.** The agent treats `booking-modified` like "booking
created," which starts a fresh record. Records are stored by booking id, so the
new one just replaces the old one, and the minutes come out right. That test
passes. But the fresh record forgets that the booking came off the waitlist, so
promotions quietly stop being counted.

### T4: The HTTP endpoint, showing history, and fixing the docs

**What it does.**

- It adds `PATCH /bookings/{bookingId}`. Only the owner can use it. An
  administrator can't, even though an administrator can *read* any booking. You
  send a new `start` and `end` (or `minutes`), a new `attendees`, or both.
  Anything you leave out stays the same. (Clause 7.)
- `GET /bookings/{id}` now shows a `modifications` list, so you can see what the
  booking used to be. There's no new endpoint. (Clause 5.)
- It fixes the three doc statements this change makes false: the event list in
  AGENTS.md, and the event list and the HTTP table in ARCHITECTURE.md. (Clause 9.)
  I make these edits by hand, because the agent isn't allowed to edit AGENTS.md.

**Where.** http-api, plus the two docs.

**Needs first.** T1. It also needs T2 for the event to actually reach the log.

**How I'll know it's done.**

```
mvn -B -pl http-api -am test
```

http-api goes above 78 tests with zero failures, and the new tests show:

- The owner's PATCH returns 200 with the new times.
- Another member gets 403.
- An administrator also gets 403.
- A PATCH into a taken slot returns 409, and a GET afterward still shows the
  original times.
- A GET after a successful change shows one history entry with the old and new
  times.

Then I search the two docs for `booking-modified` and `PATCH` to see all three
fixes, and run `mvn -B clean test`.

**How it could fool me.** The agent copies the GET endpoint's permission check,
which lets in "the owner *or* an administrator." Then an administrator could edit
anyone's booking. If the tests only try the owner and a random member, they all
pass.

### AGENTS.md rules 3 and 4: which checkpoint catches them

**T1's checkpoint catches both, but by reading the diff, not by running
tests.** A homemade overlap check that happens to give the right answers passes
every test, so the tests can't catch it.

T1's checkpoint includes two searches:

- **Rule 3, only one overlap check.** This should print nothing:
  ```
  git diff -U0 -- '*/src/main/**' | grep -nE '^\+.*\.(isBefore|isAfter|compareTo)\('
  ```
- **Rule 4, only `BookingService` writes to the waitlist.** This should show
  waitlist writes only in `BookingService.java`:
  ```
  grep -rnE 'waitlist\.(save|remove)|WaitlistStore' --include='*.java' */src/main
  ```

T1 is the only task that touches clash checks or waitlist promotion. T4 runs
both searches again too, because an HTTP handler is the next most likely place
for someone to reach into the waitlist directly.

### What I'm not touching, and why

| Not touching | Why |
|---|---|
| `billing/` | The change request says the price doesn't change: "Billing is not part of this change request. Do not re-price, re-issue, or void and re-invoice." |
| `cli/` | The change request says "Nothing in the CLI." The admin commands already show the fields that exist. |
| `accounts/` | The change request says "Nothing in account management … Accounts does not change." Editing a booking reuses the login-token and ownership checks that already exist. |
| `revenue.py` (in analytics) | The change request says the revenue report "is not part of this change," and says not to filter edited bookings out of it or read them separately. |
| any `pom.xml` | AGENTS.md rule 1: no new dependencies between modules, and none are needed. Rule 6: no JSON library. |
| tests that shipped with the starter | AGENTS.md rule 7: add new tests, never edit the existing ones. |
| `CHANGE-REQUEST.md` and `CLAUDE.md` | They're what the work gets judged against. AGENTS.md only changes for the one doc fix in T4, and I do that by hand. |

---

## Milestone 2: The guardrail

*Set up and tested at 11:14 EDT, before any task ran.*

**What I picked.** Recipe C, the filesystem lock. It makes the out-of-scope
modules read-only at the operating-system level.

**Why that one.** My agent was running in auto mode, and in auto mode it edits
files through shell commands, not just through its Edit tool. Recipes A and B
only watch the Edit tool, so a shell command would slip right past them. The
operating-system lock stops both.

**What it covers.** The three modules I'm not touching: `billing`, `cli`, and
`accounts`. They're listed in `guardrails/protected-trees.txt`:

```
billing
cli
accounts
```

The lock makes each module's `src/` folder and `pom.xml` read-only. The `target/`
build folders stay writable, so all the tests still run.

**What it can't catch.** Anything in the folders I left open, like a bad change
in core-domain, or an edit to `revenue.py` or AGENTS.md. And an agent with a
shell could just run `chmod` to unlock a folder first. That wouldn't even show up
in `git diff`.

**Proof that it works.** I told the agent to do something off-limits on purpose:

> "Use your Edit tool to add a comment at the top of
> `billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java`."

Here's what happened, copied straight from the agent's turn. The full version is
in `evidence/m2-guardrail-capture.md`.

The agent called its **Edit** tool on that file:

```
Edit(file_path = ".../f26-lab08/billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java",
     new_string = "// Guardrail probe: this comment should never land (billing is out of scope).\npackage ...")
```

The lock refused it:

```
EACCES: permission denied, open '/Users/jsp2022310/Desktop/school/CMU/Junior/Fall/17214/f26-lab08/billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java.tmp.86281.764e9a488284'
```

Then it tried the same thing through the **shell**, which is how auto mode
usually does it, on three different locked files. All three were refused:

```
sed: cli/src/main/java/edu/cmu/cs214/campusreserve/cli/CampusReserveCli.java: Permission denied
sed exit 1
(eval):3: permission denied: accounts/pom.xml
append exit 1
touch: billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Probe.java: Permission denied
touch exit 1
```

Afterward, the files were byte-for-byte the same, because the checksums matched.
`git diff` on the three modules was empty, and the probe file was never created.

**About Manual mode.** The lab asks for Manual mode because in auto mode the
agent can get around recipes A and B with shell commands. That doesn't matter for
this lock, since the operating system blocks the shell too. The capture above
shows both.

---

## Milestone 3: The run

**Which task.** T1, editing a booking in core-domain. It doesn't depend on
anything.

### When I'd take over

*Written at 11:15 EDT, before the agent touched any T1 code.*

I stop the agent and do it myself the first time any of these happens.

1. **It wanders out of scope.** The diff shows any file outside
   `core-domain/src/`, other than my own notes files. That includes any `pom.xml`
   and any test that shipped with the starter.
   *What I'd do:* restore that file from the starting commit (`3a9923d`) and
   finish T1 by hand.
2. **It writes its own overlap check.** The rule 3 search from Milestone 1
   prints a line. Calls to the existing "has this booking started yet?" helper
   don't count, because those compare one time to "now," not two time ranges.
   *What I'd do:* delete its version and write the clash check myself. I'd use the
   existing `ConflictChecker`, with the edited booking filtered out by its id.
3. **It gets stuck.** The T1 tests are still failing after its second try at
   the same failure, or it suggests editing a shipped test to make them pass.
   *What I'd do:* stop, read the failing test myself, and fix the code by hand.

**What actually happened.** None of the three fired, so I didn't take over:

- The diff only touched `core-domain/src`.
- No shipped test changed.
- The rule 3 search printed nothing.
- The tests passed on the first try.

### The two result lines

**T1's gate**, `mvn -B -pl core-domain -am test`. core-domain went from 167
tests to 202, all passing:

```
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.030 s -- in edu.cmu.cs214.campusreserve.core.service.BookingServiceModifyTest
[INFO] Tests run: 202, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**The full clean build afterward**, `mvn -B clean test`, run with the lock still
on. Every module passed. That's 453 Java tests in total: the original 418, plus
the 35 new ones.

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

The full logs are in `evidence/m3-t1-gate.log` and
`evidence/m3-closing-clean-build.log`.

### The diff, file by file

Five files, all in core-domain (`git add -N . && git diff --stat -- core-domain`):

```
 .../cs214/campusreserve/core/domain/Booking.java   |  38 +-
 .../core/domain/BookingModification.java           |  41 +++
 .../core/domain/ModificationResult.java            |  73 ++++
 .../campusreserve/core/service/BookingService.java |  78 ++++-
 .../core/service/BookingServiceModifyTest.java     | 385 +++++++++++++++++++++
 5 files changed, 610 insertions(+), 5 deletions(-)
```

- **`Booking.java`** is the history decision from Milestone 1. Each booking now
  has a list of its past changes. There's an extra constructor, so existing code
  compiles unchanged. Cancelling a booking keeps its history. A new `modified`
  method makes the edited copy and adds one history entry.
- **`BookingModification.java`** (new) is one history entry: when the change
  happened, and the old and new time and headcount. That's clause 5's "what
  changed and when."
- **`ModificationResult.java`** (new) holds the three possible results:
  Modified, Unchanged, or Rejected. It's shaped like the existing cancellation
  result, and it adds no new error codes.
- **`BookingService.java`** gets the new `modifyBooking` method (clauses 1 to 4)
  and a small helper that filters one booking out of a list. It doesn't write to
  the waitlist itself. It only calls the existing promotion method.
- **`BookingServiceModifyTest.java`** (new) has 35 tests covering items (a) to
  (j) from the checkpoint. It's a new file, not an edited shipped test.

Nothing surprising. Outside core-domain, the only changes are my own notes
(`PLAN.md`, `MILESTONE.md`, `evidence/`, and the README tool line) and the
guardrail's list file. Nothing changed in storage, notifications, http-api,
analytics, any `pom.xml`, or any shipped test.

### The one line I read hardest

Line 209 of `BookingService.java`:

```java
List<Booking> roomOthers = withoutBooking(bookings.findActiveByRoom(booking.roomId()), bookingId);
```

This is where "a booking doesn't clash with itself" happens. I checked four
things:

1. **It filters out the booking by its id.** Filtering by user would also hide
   your *other* bookings in the same room. Filtering by time would be the wrong
   thing to match on.
2. **The room comes from the saved booking**, not from the request, so there's no
   way to sneak in a room change.
3. **The filtered list goes straight into the existing
   `conflictChecker.hasConflict`** on the next line. So it uses the one official
   overlap check (rule 3), not a homemade one.
4. **Line 205 does the same thing for your own calendar**, and the booking cap
   isn't checked anywhere near here. So the cap stays waived, like clause 2 says.

### The refuter

**What I gave it.** After the clean build, I started a fresh agent session that
knew nothing about the first run. I gave it exactly three things:

- `CHANGE-REQUEST.md`
- T1's diff
- T1's one-line scope

I also told it not to open my notes. Here's the core of the brief. The full text
is in `evidence/m3-refuter-brief.md`.

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

**What it found.** It wrote 19 test probes, and 17 of them passed. So it
couldn't break the self-clash rule, the cap, refusals leaving everything
untouched, the change-that-changes-nothing, or the history. Two failed, and it
rated both as low confidence:

1. Making a booking longer can promote someone off the waitlist.
2. Asking for 0 attendees throws an exception instead of returning a clean
   "rejected."

### The one I settled: #1. I dismissed it, with evidence.

**The claim.** All times here are UTC. Using only normal API calls, the refuter
set up a waiter who's stuck on room r-1's waitlist even though their slot is
free:

1. u-2 joins r-1's waitlist for 2 to 3 pm.
2. u-2 books a different room, r-2, for 2 to 3 pm.
3. The person holding r-1 from 2 to 3 cancels. u-2 gets skipped, because they
   already have r-2 then.
4. u-2 cancels r-2. That only checks r-2's waitlist, so u-2 is left stuck on
   r-1's list with the slot free.

Then u-1 makes their 10-to-11 booking in r-1 longer, ending at 11:30, and the
waitlist pass promotes u-2. The refuter pointed to clause 4: "A modification that
only lengthens a booking frees nothing either. Running the pass and promoting
nobody is the right outcome there."

**How I checked it.** First I re-ran its test myself. It does fail, just as
claimed (`evidence/m3-refuter-probes-run.log`):

```
RefuterProbeModifyTest.lengthenPromotesAStrandedWaiterReachedThroughThePublicApi:196 lengthen promoted [Promotion[entry=WaitlistEntry[id=wl-2, roomId=r-1, userId=u-2, interval=[2026-10-20T14:00:00Z, 2026-10-20T15:00:00Z) ...]] ==> expected: <true> but was: <false>
Tests run: 19, Failures: 2, Errors: 0, Skipped: 0
```

**Why I dismissed it.** Clause 4 says an edit triggers "the same first in,
first out waitlist promotion that a cancellation triggers today." It also says
"The promotion pass runs when a successful modification changed the interval."
So the pass is supposed to run, and it's supposed to be the same pass
cancellation uses. "Promoting nobody" describes what normally happens, since
making a booking longer frees nothing. It isn't telling me to filter the pass.

To prove it, I wrote a throwaway test with three cases, all starting from the
refuter's exact stuck-waiter setup:

- **Cancellation does the same thing, using only the starter's code.**
  Cancelling u-1's unrelated 10-to-11 booking also promotes u-2 into 2 to 3 pm,
  which is time that cancellation didn't free.
- **Making it longer promotes exactly the same person into exactly the same
  slot.** So it really is "the same" pass.
- **Without a stuck waiter, making a booking longer promotes nobody**, just like
  clause 4 says.

All three passed:

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in edu.cmu.cs214.campusreserve.core.service.SettleFinding1Test
[INFO] BUILD SUCCESS
```

Also, T1 didn't change the promotion code at all. It only added one call to it.
To make a longer booking promote nobody here, I'd have to write a second,
different promotion pass, which breaks "the same," or change how cancellation
already works. Both are outside T1. The stuck waiter is a result of how
cancellation already works.

The log is `evidence/m3-finding1-settle.log`, and the test source is
`evidence/SettleFinding1Test.java.txt`.

**Finding #2: not settled, but noted for T4.** Asking for 0 attendees throws an
exception. Creating a booking already works that way, so it's consistent. I
still added one item to T4's checkpoint: a PATCH with `attendees=0` should get a
400. The handler should check that before it calls the service, the same way POST
does.

**Cleanup.** I deleted both probe test files and kept copies as `.txt` files in
`evidence/`. Afterward, the diff was back to exactly T1's five files. Then I
unlocked the guardrail and ran one more full clean build (453 tests, all
passing) and the Python tests (137 passed).
