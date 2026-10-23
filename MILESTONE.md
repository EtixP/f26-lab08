# Lab 8: results and what to show the TA

This page is the recitation script. Everything the TA grades is in `PLAN.md`,
backed by the files in `evidence/`. Every timestamp below comes from
`evidence/timeline.txt`.

## Before recitation (checklist)

- [ ] **Commit and push to your fork** (`origin` = `EtixP/f26-lab08`). Nothing
  has been committed yet. Suggested message: `Lab 8: plan, guardrail, T1
  (booking modification in core-domain)`. Leave out `lab08.md`, the handout,
  unless you want it in the fork.
- [ ] **Check CI on the fork.** Open the Actions tab. If it says workflows are
  disabled on this fork, click *enable*. After the push, CI should go green, and
  it runs the same `mvn -B test` + `pytest` as the local runs below.
- [x] **Unlock the guardrail.** `guardrails/unlock.sh` has already been run after
  the task (see the bottom of this page). If the TA wants to see it bind live,
  run `guardrails/lockdown.sh` again and try the demo in "Milestone 2" below.

Baseline before any edit: `mvn -B test` gave **418** Java tests green, and
`pytest` gave **137 passed** (11:13 EDT).

---

## Milestone 1: the plan

**Show:** the "Milestone 1" section of `PLAN.md`. The snapshot taken right after
it was written is `evidence/PLAN-after-milestone1.md` (11:13 EDT, before any
source file changed).

**Tell the TA:**

- There are **four tasks** in dependency order:
  - **T1** (core-domain): modify a booking, CR 1–5.
  - **T2** (core-domain events + storage + notifications): the `booking-modified`
    event, the log line, and the owner notification.
  - **T3** (analytics): the ledger folds `booking-modified`, so utilization
    counts the current length.
  - **T4** (http-api + docs): `PATCH /bookings/{id}`, owner only, with history on
    `GET /bookings/{id}` and the CR-9 doc fixes.
- Each task has a gate command with expected counts, named tests that would
  convince me, and one way it stays green while wrong.
- Why T2 is not folded into T1: `DomainEvent` is **sealed**, and `notifications`
  switches over it exhaustively. Adding the event in core-domain alone keeps the
  core-domain gate green while notifications stops compiling. So the event, its
  log line, and its notification ship together in T2, and T2's gate is
  `-pl storage,notifications -am` followed by a cold build.
- **Rules 3/4:** T1's checkpoint catches them through its diff-audit greps, not
  its test count. A second overlap predicate that agrees with
  `TimeInterval.overlaps` passes every test.
- **Out of scope:** `billing`, `cli`, `accounts`, `revenue.py`, every `pom.xml`,
  shipped tests, and `CHANGE-REQUEST.md`/`CLAUDE.md`. Each has a quoted CR
  clause or AGENTS.md rule.
- Design choices the CR left open, decided in M1:
  - history lives on `Booking`, in a `modifications` list
  - no new rejection codes
  - a no-op succeeds and records nothing
  - a collision is rejected and never waitlisted

**If asked "when did you write it?"** At 11:13 EDT. The T1 run started at 11:15
EDT and the first T1 file was edited after that. See `evidence/timeline.txt` and
the two `PLAN-*.md` snapshots.

---

## Milestone 2: the guardrail

**Show:** the "Milestone 2" section of `PLAN.md` and
`evidence/m2-guardrail-capture.md`.

**Tell the TA:**

- **Recipe C** (filesystem lockdown) on `billing`, `cli`, `accounts`, the
  out-of-scope modules from M1, via `guardrails/protected-trees.txt`.
- The capture shows the agent's own **Edit tool call** on
  `billing/.../Money.java` coming back `EACCES: permission denied`, plus a
  **Bash** attempt (`sed -i` on a CLI file, `>>` on `accounts/pom.xml`, `touch`
  in billing) coming back `Permission denied` three times. Checksums before and
  after match, and `git diff` of the locked trees is empty.
- About Manual mode: the agent ran in the VS Code extension's auto mode. The
  guide asks for Manual mode because auto mode writes through Bash, which
  recipes A and B cannot see. Recipe C is enforced by the OS, so it blocked the
  Bash route too, and the capture shows both.
- **The one line:** it cannot catch anything in the trees left open (a
  wrong-but-compiling core-domain change, `revenue.py`, which is Python and not
  lockable, or `AGENTS.md`), and an agent with a shell can `chmod -R u+w` its way
  past it without leaving a trace in `git diff`.

**Live demo, if the TA wants one:**
```
guardrails/lockdown.sh
claude --permission-mode manual
> use your Edit tool to add a comment at the top of billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java
# expect EACCES; then:  guardrails/unlock.sh
```

---

## Milestone 3: the task run

**Show:**

1. The "Milestone 3" section of `PLAN.md`.
2. The diff: `git add -N . && git diff -- core-domain` (5 files, +610/−5).
3. Both result lines (below).
4. The refuter's brief, in `evidence/m3-refuter-brief.md`.
5. The finding's evidence (below).

**Takeover point** (written at 11:15 EDT, before the first T1 edit). These are
three concrete triggers:

1. Any path outside `core-domain/src/` in the diff, including a shipped test or
   a `pom.xml`.
2. The rule-3 grep printing a hand-rolled `isBefore`/`isAfter`/`compareTo`.
3. The gate still red after two attempts at the same failure, or a proposal to
   edit a shipped test.

Each trigger names an action. **None fired.**

**Result lines:**

- Gate `mvn -B -pl core-domain -am test`: `Tests run: 202, Failures: 0, Errors: 0, Skipped: 0` (167 → 202) and `BUILD SUCCESS`. The log is `evidence/m3-t1-gate.log`.
- Closing `mvn -B clean test`: every module green, core-domain 202 / storage 63 / notifications 41 / accounts 19 / billing 19 / http-api 78 / cli 31, total **453 = 418 + 35**, `BUILD SUCCESS`. The log is `evidence/m3-closing-clean-build.log`.

**The line read hardest:** `BookingService.java:209`
`List<Booking> roomOthers = withoutBooking(bookings.findActiveByRoom(booking.roomId()), bookingId);`
I checked four things:

- the exclusion is by booking **id**, not by user or interval
- the room comes from the stored booking, not the caller
- it feeds `conflictChecker.hasConflict` (rule 3)
- the booking cap is never consulted

**Refuter:** a fresh Claude Code subagent. It was given only
`CHANGE-REQUEST.md`, the core-domain diff, and T1's one-line scope, and was told
not to read `PLAN.md`/`evidence/`. The brief is in
`evidence/m3-refuter-brief.md`. It wrote 19 probes. 17 were green, and 2 were
red, both rated low confidence.

- **Settled finding #1, "lengthening promotes a stranded waiter": dismissed
  with evidence.**
  - I re-ran the refuter's probe first, and it was red as claimed
    (`evidence/m3-refuter-probes-run.log`).
  - I then built the same stranded state and showed that the **shipped
    cancellation pass** promotes the same waiter from unrelated freed time.
    CR 4 says modification uses "the same … promotion that a cancellation
    triggers" and "The promotion pass runs when a successful modification
    changed the interval."
  - The lengthen promotes exactly the same waiter, and with nothing stranded a
    lengthen promotes nobody.
  - The evidence is `evidence/m3-finding1-settle.log`, with the source in
    `evidence/SettleFinding1Test.java.txt`. The T1 diff does not touch
    `promoteWaitlist`; it only adds a call to it.
- **Finding #2** (`attendees=0` throws instead of being rejected) was not
  settled. It matches the existing `requestBooking` contract, and I added a
  400-on-`attendees=0` check to T4's checkpoint.
- **Probe files deleted.** Copies are kept as `.txt` in `evidence/`.

**Say this if asked "what was the strongest claim and how did you check it?"**
I reproduced the refuter's red probe, then ran the same scenario through the
starter's own cancellation path. It promotes the same waiter, so the
modification is doing exactly "the same" pass that CR 4 requires.

---

## Final state of the working tree

- The guardrail is unlocked (`guardrails/unlock.sh`). Locking and unlocking
  leave nothing in `git diff`.
- A final `mvn -B clean test` gave **453** Java tests and BUILD SUCCESS. Python
  `pytest` gave **137 passed**, since T1 changed no Python.
- Changed or added files:
  - `PLAN.md`
  - `README.md` (tool line)
  - `MILESTONE.md` (this file)
  - `guardrails/protected-trees.txt`
  - `evidence/`
  - 5 files in `core-domain` (3 main, 1 new main, 1 new test; see the diff
    table in `PLAN.md`)
- **Not committed or pushed.** Commit and push before recitation so the TA can
  see it on the fork.

---

## Files to know

| File | What it is |
|---|---|
| `PLAN.md` | All three milestones, the graded write-up |
| `evidence/timeline.txt` | Timestamps for each step, written as it happened |
| `evidence/PLAN-after-milestone1.md`, `evidence/PLAN-before-task-run.md` | Plan snapshots that prove the order |
| `evidence/m2-guardrail-capture.md` | The guardrail capture |
| `evidence/m3-t1-gate.log`, `evidence/m3-closing-clean-build.log` | Full Maven output for both result lines |
| `evidence/m3-refuter-brief.md` | The refuter brief, verbatim |
| `core-domain/.../service/BookingServiceModifyTest.java` | T1's 35 new tests |
| `README.md` (last section) | The tool and model line |
