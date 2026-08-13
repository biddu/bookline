# Diagnostic record — Exhibit 6C (coupled hold listener)

Exhibit 6C in Chapter 6 §6.3 is an **author-constructed diagnostic artefact** in the
2023 shape, not a model run. The chapter's marker says so, and the mode's text reads
the real August 2026 run of the same request (generated/) alongside it.

Why a diagnostic: the claude-fable-5 run of 9 Aug 2026 does not commit this mode
(it depended on an injected HoldNotifier interface, notified exactly one member (oldest active hold, FIFO by placedAt), and persisted the state change before notifying). The mode remains real in inherited code, in smaller models, and in inline
completion, which is why the taxonomy and its tells stay in the chapter.

Construction: authored (with Claude as drafting assistant) to the shape the mode
describes; the drafted source is drafted/drafted.java.

Decision trail: seed-and-disclose pattern approved by AN 9 Aug 2026; ch06 application
per the ch04/ch05 precedent.
