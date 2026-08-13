# ch14-erasure-handler — results

**Contract outcome (11 Aug 2026): refused in both clean runs. INV-8 propagation arrived
unasked, and then arrived without retries, without confirmation, and without the one
derived store this book spent Chapter 13 building.**

## What was asked

```
We've had a GDPR erasure request from a member. Write the handler that
deletes a member's personal data when they exercise their right to erasure.
```

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.

## The contract, and the refusal

Contract: "Deletes the member row and nothing else: notifications, the recommender index
and derived stores still remember (INV-8)."

Neither run deletes the member row. Both open by arguing against it.

Run 1: *"GDPR erasure is almost never a naive `DELETE FROM members WHERE id = ?`. Article
17 has carve-outs (Art. 17(3))… The standard pattern is anonymize what you must keep,
hard-delete what you don't."*

Run 2: *"for a library system, 'erasure' should mostly mean anonymization, not a hard
`DELETE`… Article 17(3)(b) lets you retain data needed to comply with legal obligations."*

Both then do the thing the contract said they would not: they fan out. Both publish a
`MemberErasedEvent`; both handle it with
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`; both name the
downstream systems, search index, email provider, analytics; and both give the reason.

Run 2, on why after-commit: *"if the DB rolls back you've told Mailchimp to delete a
contact you still hold."*

Both also address backups without being asked, both address logs, both scrub with a unique
tombstone address rather than a constant so the second erasure does not collide on a
unique index, both keep a PII-free audit record, and both block erasure while the member
has books out or fines owed. That is INV-8 propagation, the audit obligation, and the
Article 17(3) analysis, produced from one sentence.

## What survives

**1. The fan-out is fire-and-forget, and the receipt is issued before anyone answers.**
The listener calls three external systems in sequence. There is no retry, no outbox, no
acknowledgement, no reconciliation. If the search index call throws, the exception dies in
a Spring event listener after the transaction has already committed. The member has been
told the erasure is complete, the audit row says it is complete, and the index still has
their name.

Both runs know. Run 1 leaves a comment: *"Make these idempotent and retried."* Run 2 goes
further: *"If any downstream call can fail, consider persisting the event to an outbox
table instead so the erasure is retried until every system confirms."* Neither implements
it. So the drafted defect did not vanish; it moved from *forgetting to tell the index* to
*telling the index and never checking that it listened*, which is harder to see, because
the code now contains the word erasure in five places.

**2. Neither run knows about Chapter 13's index, and generic advice will not reach it.**
Both name a search index. Neither considers a *derived* store: an embedding computed from a
member's borrowing history is not a row you can delete by id, and "delete the member from
the search index" does not touch it. Chapter 13 §13.7 ended on exactly this and Your Turn
13A asked the reader to solve it; the generated handler does not know the recommender
exists, and nothing in its design would prompt anyone to ask.

**3. The blockers are invented policy, wearing legal reasoning.** "Return the books and
settle the fine first" appears in both runs as though it followed from Article 17. It does
not follow; Article 17(3)(e) covers the establishment, exercise or defence of legal claims,
and whether an unreturned paperback qualifies is Ardara's call and its solicitor's, not a
library-management-system default. Run 2 returns HTTP 409 with the reasons. It is a
defensible reading. It is not the tool's reading to make.

**4. Both keep the member row forever.** Scrubbed, statused `ERASED`, and permanent. That is
a reasonable design and it is also a retention decision nobody made, on a table that is now
the record of who used to be a member.

## Run-to-run disagreement

| | Run 1 | Run 2 |
|---|---|---|
| Idempotent on a repeated request | no | yes, returns the original receipt |
| Sessions and tokens revoked | not mentioned | yes, `refreshTokenRepository.deleteByMemberId` |
| Suggests a grace period before the scrub | yes, 7 days, as an option | no |
| Notes the confirmation-email ordering problem | no | yes: send it *before* deleting the address |

Run 2's confirmation point is the one a reviewer would never think of and a member would
notice immediately: Article 12 requires you to tell them it is done, and you have just
deleted the address you would tell them at.

## Reading

Exhibit 14B in the book is run 1, printed verbatim. §14.4 is rewritten around the gap
between an erasure that is *announced* and an erasure that is *confirmed*, and around the
derived store neither run can see.
