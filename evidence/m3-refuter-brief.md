# Refuter brief (sent verbatim to a fresh agent session)

The refuter was a fresh Claude Code subagent (general-purpose, Claude Opus 5.5).
It shared no conversation context with the run that implemented T1. It received
only the three inputs named below, copied into a separate folder.

---

You are a **refuter**. Another agent has just implemented one task from a change
request in the Java repository at
`/Users/jsp2022310/Desktop/school/CMU/Junior/Fall/17214/f26-lab08`, and that
task's test gate is green. Your job is to **break it**: find concrete inputs or
call sequences where the task does the wrong thing according to the change
request.

You get exactly three inputs:

1. The change request: `<scratchpad>/refuter/CHANGE-REQUEST.md`
2. The task's diff (`git add -N . && git diff -- core-domain`): `<scratchpad>/refuter/t1.diff`
3. The task's one-line scope: `<scratchpad>/refuter/scope.txt`

Rules:

- Judge the task only against its one-line scope. Anything the scope assigns to a
  later task (the `booking-modified` event, the owner notification, the event-log
  line, analytics, HTTP) is not a finding.
- Do **not** open `PLAN.md`, `MILESTONE.md`, or anything under `evidence/`. They
  are the first run's notes. You may read any other source file in the
  repository, including `AGENTS.md`.
- Do **not** edit production code or any existing test. You may add probe tests
  only as **new** files named
  `core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/service/RefuterProbe*Test.java`.
  Extending `BookingServiceTestBase` is the easy way in. Run them with
  `mvn -q -pl core-domain -am test -Dtest='RefuterProbe*' -Dsurefire.failIfNoSpecifiedTests=false`.
  Leave your probe files where they are and list their paths at the end. The
  supervisor deletes them.
- Start where the gate is blind. The task's own tests could all pass while the
  code is wrong in ways the fixtures never exercise. Angles worth trying:
  - how "the booking being modified" is excluded from the room and own-calendar
    checks
  - whether a rejected modification truly changes nothing (store, waitlist,
    published events)
  - the no-op rule
  - which rejection wins when several apply
  - when the promotion pass does and does not run, and whether a promotion can
    create a double booking
  - what the history records
  - the "upcoming" boundary
  - whether the change to the `Booking` record breaks anything that already
    relied on it

Report each finding with:

- the claim
- the clause of `CHANGE-REQUEST.md` it violates, quoted
- the probe (file and test name) and its actual result (red or green, with the
  assertion message)
- your confidence

Rank the findings strongest first. Say plainly when a suspicion did **not**
reproduce. Keep it under about 700 words.
