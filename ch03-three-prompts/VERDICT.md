# Verdict — ch03-three-prompts (9 Aug 2026)

Contract vs reality: the drafted v1 (public fields, String dates, title/name identity) did
not occur; claude-fable-5's vague output is polished (private fields, LocalDate, UUID id)
and still invents policy wholesale: 14-day period, 2 renewals, $0.25/day late fee capped
at $10.00, money in double, clock read inside a convenience constructor, String identities.
v2 exceeded the drafted expectation: no setters at all, and the model flagged its invented
constants in prose (the flag lives in the transcript, not the code). v3 met its contract
fully; its self-report names the single-open-loan rule it cannot enforce. §3.3 was
redrafted to the real evidence on 9 Aug 2026.
