# Bookline exhibits

The generated artefacts behind *Software Engineering Thinking: Judgment, Review,
and Craft When AI Writes the Code* by Avishek Nag.

Every exhibit printed in the book is a real generated artefact. This repository
holds all of them, unedited, and it holds more of each than the printed page had
room for.

**Thirty-one sets of runs. Fifty-eight recorded responses. Twenty-nine of them
printed in the book as exhibits.** Sixteen of the sets were run more than once,
because a single run is an anecdote.

## How to read it

Each top-level directory is one exhibit set, named for its chapter, and the book
prints these paths exactly as they appear here. Every set holds the same things:

| | |
|---|---|
| `PROMPT.md` | what was sent, word for word, with the tool, the model, the date and the tool-call count |
| `generated/` | what came back, unedited |
| `drafted/` | the artefact the chapter was written around **before** the run, kept so you can see what the run refused |
| `RESULTS.md` or `VERDICT.md` | whether the defect the chapter expected actually appeared |
| `DIAGNOSTIC_RECORD.md` or `SEED_RECORD.md` | present only where a defect had to be built by hand because no run would produce it, stating what was seeded and why |

**Read the results files even if you skip the code.** Roughly two thirds of them
record that the expected defect did *not* appear, and say what the chapter did
instead. That is the most useful thing here, and it is the part a book cannot
fake.

## The method

Every run was produced in a fresh session that had been given nothing but the
prompt printed on the page: no chapter draft, no invariant list, no description
of the defect the author was hoping to see, and an explicit instruction not to
use tools. The tool-call count is recorded for each so you can check that.

The one exception is disclosed rather than hidden.
`ch12-consortium-schema/generated/contrast_informed_runs.md` holds a pair of runs
that *did* use their file tools, found the project directory, and answered from
the locked design document. They are kept, labelled, and used in §12.5 as a
contrast condition, because the two conditions differ in exactly one variable and
the difference is the argument of that section.

## Reproducing a run

The prompts are in the repository verbatim. Send one to any assistant, in a fresh
session, with no other context, and compare. You will not get the same output.
That is the finding, not a defect in the method: `ch01-first-loan` holds five runs
of one thirteen-word prompt, and they disagree with each other about how long a
loan lasts.

`ch07-generated-suite` is the one that runs rather than reads. The suite, the core
under test, the mutation harness and the raw logs are all here, and the experiment
re-runs in a few minutes on any machine with a JDK.

## Citing this

If you use these artefacts in teaching or research, cite the archived snapshot
rather than the repository, so the version you used stays fixed.

## Licence

Three kinds of material, three statuses. See `LICENSE` for the full text.

| | |
|---|---|
| **What I wrote** — prompts, results, verdicts, records, READMEs, and the arrangement of the collection | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| **Code I wrote** — everything under `drafted/`, the hand-built diagnostics, the core under test, the test harness | MIT |
| **Everything under `generated/`** | No copyright asserted. See below. |

The generated responses are not licensed here because I do not believe they are
mine to license. The US Copyright Office's position is that copyright requires
human authorship and that writing a prompt is not by itself authorship. On that
reading these files have no copyright owner. The law is unsettled and varies by
jurisdiction, so take that as an honest reading rather than legal advice.

Please cite the archived snapshot rather than this repository, so the version you
used stays fixed. That is a request grounded in research practice, not a licence
condition.

Suggested attribution:

> Avishek Nag, "Bookline exhibits", companion repository to *Software Engineering
> Thinking* (2026), https://github.com/biddu/bookline, licensed CC BY 4.0.
