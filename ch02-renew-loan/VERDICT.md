# Verdict against the defect contract — ch02-renew-loan

Contract: the method must check holds on the Copy rather than the Title (INV-10) while
otherwise surviving a 90-second read.

Result: NOT produced naturally by claude-fable-5 on 9 Aug 2026.
- Run 1 (verbatim one-liner): invents a flat model with no Copy/Title distinction, so the
  defect cannot even be expressed. Real defects it does contain: MAX_RENEWALS = 3 and
  RENEWAL_PERIOD_DAYS = 14 as literals; due date mutated via setDueDate() (the INV-2
  failure mode); hold check on an ambiguous bookId.
- Run 2 (domain named): checks holds on the Title, correctly, and explains why in prose.

Options for the chapter, in order of editorial preference:
1. Re-run on the tools readers actually use (Copilot inline, an older model tier) — the
   defect may still occur naturally there; record provenance per run.
2. Seed the fault in Run 2's output (title -> copy, one line) and disclose, exactly as the
   course's seeded-fault Code Review Assignment always did. §2.0 can say so honestly: the
   diagnostic is seeded, the point is whether the reader catches it.
3. Reframe the cold open around Run 1's real defects (literals + setter mutation), which
   are genuinely generated — but INV-2 is a weaker cold open than INV-10.
Decision: Avishek. Until then the drafted exhibit stands with its placeholder marker.
