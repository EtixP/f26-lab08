# Lab 8: what to show and say at recitation

**How to use this page.** For each milestone, open what's under **Show**, then
read what's under **Say**. Quick answers to likely questions come right after.
The detailed write-up is in `PLAN.md`, and the proof is in `evidence/`.

---

## Before you walk in

- **The work is on the `lab08` branch of your fork.** Make sure the latest commit
  is pushed. If the TA looks at `main`, merge `lab08` into `main` first.
- **CI is on.** Check the fork's Actions tab. If it says workflows are disabled,
  click enable. It should be green.
- **The guardrail is unlocked.** If the TA wants to see it working live, follow
  the steps at the end of Milestone 2.

---

## Milestone 1: the plan

**Show:** the Milestone 1 section of `PLAN.md`.

**Say:**

> "I split the change request into four tasks.
>
> T1 is the core rules for editing a booking, in core-domain. T2 adds the new
> event, writes it to the log, and notifies the owner. T3 teaches the Python
> utilization report about the new event. T4 adds the PATCH endpoint, shows the
> booking's history, and fixes the docs.
>
> For each task I wrote down the exact test command, what numbers I expect to
> see, which tests have to exist, and one way it could pass its tests and still
> be wrong.
>
> One thing I want to point out: I kept the event out of T1 on purpose. The event
> types are sealed, and notifications has switch statements that have to cover
> every type. So if I added an event in core-domain alone, core-domain's tests
> would pass, but notifications wouldn't even compile.
>
> For rules 3 and 4, it's T1's checkpoint that catches them. But it does it by
> reading the diff, not by running tests. A homemade overlap check that gives the
> right answers would pass every test, so I search the diff for one instead.
>
> I'm not touching billing, the CLI, accounts, the revenue report, any pom file,
> or any shipped test. Each one has a reason quoted from the change request or
> from AGENTS.md."

**If they ask...**

- *"When did you write this?"*
  "At 11:13, before any code changed. The task started at 11:15. You can see it
  in `evidence/timeline.txt`, and there's a snapshot of the plan from right then
  in `evidence/PLAN-after-milestone1.md`. I reworded it later to read better out
  loud, but the content's the same. The original wording is in my first commit."
- *"Why put the history on the booking?"*
  "It keeps it simple. The booking carries its own list of changes, so there's no
  new store, and the read endpoint can just show it. I also added a second
  constructor so none of the existing code had to change."
- *"What happens if the edit clashes?"*
  "It's just refused, and the booking stays exactly as it was. Unlike a new
  booking, it doesn't put you on the waitlist."

---

## Milestone 2: the guardrail

**Show:** the Milestone 2 section of `PLAN.md`, and
`evidence/m2-guardrail-capture.md`.

**Say:**

> "I used recipe C, the filesystem lock. It makes billing, CLI, and accounts
> read-only. Those are the three modules my plan says not to touch.
>
> I picked it because my agent was in auto mode, and in auto mode it edits files
> with shell commands too. Recipes A and B only watch the Edit tool, so they'd
> miss those. The lock is enforced by the operating system, so it stops both.
>
> To test it, I told the agent to add a comment to billing's Money.java. Here's
> its Edit tool call, and here's the error that came back: permission denied.
> Then it tried through the shell on three different locked files, and all three
> got permission denied too. The files didn't change. The checksums match, and
> `git diff` is empty.
>
> What it can't catch: anything in the folders I left open, like a bad change in
> core-domain. And an agent could just run `chmod` to unlock a folder, and that
> wouldn't even show up in the diff."

**If they ask...**

- *"The lab said Manual mode. Why auto?"*
  "Manual mode matters for recipes A and B, because in auto mode the agent can go
  around them with shell commands. The lock is enforced by the operating system,
  so it blocks the shell too, and the capture shows both kinds of attempt. I can
  re-run it in Manual mode right now if you'd like."

**Live demo, if they want one:**

```
guardrails/lockdown.sh
claude --permission-mode manual
> use your Edit tool to add a comment at the top of billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java
# you should see a permission denied error. Then:
guardrails/unlock.sh
```

---

## Milestone 3: the task run

**Show:**

1. The Milestone 3 section of `PLAN.md`.
2. The diff: `git diff 3a9923d -- core-domain`. That's five files.
3. The two result lines (they're in `PLAN.md`).
4. The refuter's brief, in `evidence/m3-refuter-brief.md`.
5. The evidence for the finding, in `evidence/m3-finding1-settle.log`.

**Say:**

> "I ran T1, editing a booking in core-domain.
>
> Before it started, I wrote down when I'd take over. There were three
> triggers. One: it edits anything outside core-domain, or any shipped test.
> Two: it writes its own overlap check. Three: it's still failing after two
> tries, or it wants to edit a shipped test. None of those happened.
>
> T1's tests went from 167 to 202, all passing. That's 35 new tests. Then a full
> clean build passed too, with 453 Java tests: the original 418 plus the 35 new
> ones.
>
> The diff is five files, all in core-domain. There's the booking with its
> history, two new small classes, the new method in BookingService, and the new
> test file. No shipped test was touched.
>
> The line I read hardest is line 209 of BookingService. That's where the edit
> ignores the booking itself when it checks for clashes. I made sure it filters
> by the booking's id, not by user or time. I made sure the room comes from the
> saved booking, so you can't switch rooms. I made sure it goes through the
> existing ConflictChecker, so there's no second overlap check. And I made sure
> the booking cap isn't checked.
>
> Then I started a fresh agent session as a refuter. I gave it only the change
> request, the diff, and the one-line scope, and told it to break the task. It
> wrote 19 probes, and 17 passed. Two failed.
>
> I settled the stronger one. It said that making a booking longer can promote
> someone off the waitlist, and clause 4 says lengthening should promote
> nobody. I re-ran its test, and it does fail. But I showed that a plain
> cancellation in the starter code promotes the exact same person in the exact
> same setup. Clause 4 says an edit uses 'the same' promotion as a cancellation,
> and that the pass runs whenever the time changes. So my code is doing what the
> change request asks. That person was already stuck on the waitlist because of
> how cancellation already works, not because of my change.
>
> The other finding was that 0 attendees throws an exception. Creating a booking
> already does that, so I noted it for T4: the PATCH endpoint should return a 400
> for that.
>
> I deleted the refuter's probe files and kept copies in the evidence folder."

**If they ask...**

- *"What was the strongest claim, and how did you check it?"*
  "The longer-booking one. I re-ran the refuter's failing test to confirm it,
  then ran the same setup through the starter's own cancellation. It promotes
  the same person into the same slot, so mine is 'the same' pass that clause 4
  asks for."
- *"Why didn't you just fix it?"*
  "Fixing it would mean writing a second, different promotion pass, which breaks
  'the same.' Or it would mean changing how cancellation already works. Both are
  outside T1."
- *"Why didn't you take over?"*
  "None of my three triggers fired. The diff stayed in core-domain, the overlap
  search came back empty, and the tests passed on the first try."

---

## Where everything is

| File | What it is |
|---|---|
| `PLAN.md` | The full write-up for all three milestones |
| `evidence/timeline.txt` | When each step happened |
| `evidence/PLAN-after-milestone1.md` | The plan exactly as it was before any code |
| `evidence/PLAN-before-task-run.md` | The plan, guardrail, and takeover point, before the run |
| `evidence/m2-guardrail-capture.md` | The guardrail blocking the agent |
| `evidence/m3-t1-gate.log` | Full output of T1's test run |
| `evidence/m3-closing-clean-build.log` | Full output of the clean build |
| `evidence/m3-refuter-brief.md` | Exactly what the refuter was told |
| `evidence/m3-refuter-probes-run.log` | My re-run of the refuter's probes, with 2 failing |
| `evidence/m3-finding1-settle.log` | The test that settled finding #1 |
| `evidence/RefuterProbeModifyTest.java.txt`, `evidence/SettleFinding1Test.java.txt` | Copies of the deleted probe files |
| `core-domain/.../BookingServiceModifyTest.java` | T1's 35 new tests |
| `README.md` (last section) | Which tool and model I used |
