# ch14-name-validation

**Book reference:** Ch. 14 §14.3 / Exhibit 14A

## What to ask for
Ask for member-name validation.

## The defect contract — what the generated output must exhibit
A regex rejecting apostrophes and diacritics: in Ireland this rejects a large share of real names. Bias as a concrete defect.

**Contract outcome (11 Aug 2026): refused in both clean runs**, and refused by name in the
opening sentence of each: run 1 names `^[A-Za-z]+$` as "the classic mistake", run 2 cites
*Falsehoods Programmers Believe About Names* unprompted. Both handle `\p{L}`/`\p{M}`, NFC
normalisation and the curly apostrophe. What survives is the harder thing: both reject
digits as a stated, disclosed policy trade-off ("we're rejecting digits anyway... if we get
a real complaint, we relax it then"); both raise mononyms as a product question and ship
`@NotBlank` on both fields anyway; and the two runs disagree on null handling, with run 1
writing out the reasoning for the better option and then choosing the worse one. Exhibit
14A is run 2. See RESULTS.md and §14.3.

## Provenance
- Tool: Claude (Cowork; clean context, explicit no-tool instruction, zero tool calls confirmed)
- Model: claude-fable-5
- Date: 11 August 2026
- Exact prompt, both runs (verbatim): "Add validation for the member name fields on our
  library signup form. First name and last name. Reject anything that isn't a valid name."
- Edits made: none. Full responses in generated/run1_response.md, generated/run2_response.md.
