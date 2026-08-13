# ch14-name-validation — results

**Contract outcome (11 Aug 2026): refused in both clean runs, and refused by name in the
first sentence of each. The bias defect did not disappear. It changed from an error into
a disclosed policy decision made by nobody accountable.**

## What was asked

```
Add validation for the member name fields on our library signup form.
First name and last name. Reject anything that isn't a valid name.
```

- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls)
- Model: claude-fable-5
- Date: 11 August 2026
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.

## The contract, and the refusal

Contract: "A regex rejecting apostrophes and diacritics: in Ireland this rejects a large
share of real names."

Both runs open by warning against precisely that, before any code.

Run 1, second sentence: *"Real names include things like `O'Brien`, `Anne-Marie`,
`van der Berg`, `José`, `Nguyễn`, `李`, and single-letter names. The classic mistake is
`^[A-Za-z]+$`, which rejects a big chunk of your actual members."*

Run 2, second sentence, citing the canonical source unprompted: *"'valid name' is a
famously dangerous requirement (see* Falsehoods Programmers Believe About Names*). Real
members will show up named `O'Brien`, `Anne-Marie`, `van der Berg`, `José`, `Nguyễn`,
`Æbelø`, or `李`."*

Both then implement `\p{L}` and `\p{M}`, NFC normalisation so a decomposed é matches a
precomposed one, and the curly apostrophe U+2019. Run 1 gives the reason for that last
one and it is the best line in either response: *"mobile keyboards autocorrect `'` to `’`
— without it you'll reject half the iPhone users named O'Connor."*

An Irish library would not lose a single O'Brien, Ní Shúilleabháin or Seán to either
validator. The 2023-era defect this exhibit was written to display is gone.

## What survives, and it is worse to review

**1. The tool makes the policy call and tells you afterwards.** Both runs reject digits.
Run 2 states the reasoning explicitly: *"legally, names like 'X Æ A-12' exist in a few
jurisdictions. We're rejecting digits anyway — that's a deliberate trade-off (digits are
overwhelmingly typos or junk input). If we get a real complaint, we relax it then."*

Read that again. It identifies a class of real people, decides to exclude them, states the
trade-off, sets a threshold for revisiting ("if we get a real complaint"), and ships. That
is a policy decision about who may hold a library card, taken by a tool, disclosed in a
numbered note underneath the code, and inherited by whoever merges the pull request. It is
defensible. It is also nobody's decision, and the person who will be asked to defend it at
a council meeting never made it.

**2. Mononyms: raised by both, solved by neither.** Run 1's closing line: *"if the library
serves a community where mononyms are common (single legal name), consider making last
name optional and only requiring one of the two — that's a product decision, not a code
one, but worth raising before this ships."* Run 2 says the same at its item 2. Both are
right. Both ship a form with `@NotBlank` on both fields. Chapter 11's category exactly:
raised, not implemented, and the code that runs on Monday is the code above the note.

**3. Both forbid repeated separators and edge punctuation.** Run 2's test asserts that
`Anne--Marie` and `O''Brien` are invalid, and both reject a name beginning or ending in an
apostrophe or hyphen. These rules are almost always right and are not derived from any
register of real names; they are the tool's idea of tidiness, applied to a field where the
cost of being wrong falls entirely on the person being refused.

**4. The two runs disagree on null handling, and one of them knowingly picks the worse
option.** Run 2 returns `true` on null, deferring to `@NotBlank` so an empty field yields
"required". Run 1 returns `false`, and says in its own notes that the convention is the
other way and that returning true *"gives users a 'required' message rather than 'invalid'
for an empty field"* — then keeps `false` "for belt-and-braces". A member who tabs past
the field is told their name is invalid. Same prompt, same day, opposite choice, with the
reasoning for the better choice written out beside the worse one.

**5. Both cap length at 100 characters** without saying where 100 came from.

## Reading

Exhibit 14A in the book is run 2, printed verbatim, and §14.3 is rewritten around the
finding that the bias moved rather than went: from a regex a reviewer could have caught by
reading it, to a disclosed trade-off a reviewer will read, find reasonable, and approve.
