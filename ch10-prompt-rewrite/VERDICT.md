# Verdict — ch10-prompt-rewrite

The C-I-E rewrite held: 16 types, 565 lines, no SQL, no mail, imports confined to the
permitted modules, events published at the boundary, the concurrent-loan limit enforced
in exactly one place through the injected CirculationService, and a closing self-report
of unhandled edge cases (the Evaluation Cue honoured). Compared against the vague run
(ch05), the pair demonstrates §10.3's claim with real evidence: the vague prompt bought
hardcoded tier limits, inlined SMTP and invented policy; the specified prompt bought
none of them. Residue for the chapter's honesty note: the model still chose its own
event names and hold-expiry behaviour, neither of which the prompt pinned.
