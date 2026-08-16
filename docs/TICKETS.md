# Writing tickets for this project

A ticket is an expression of **my** (the owner's) will. Write the description in my
words, made declarative. Your reasoning, findings, diagnoses, and implementation
notes are **yours**: they go in a comment, never in the description, and never
dressed up as if they were the request.

This file exists because the tickets TFI-82 to TFI-100 were originally written with
embellished, invented prose. Follow the rules below so that never happens again.

## What NOT to produce

- **No embellishment.** No flowery framing, no "our" (this is a one-person
  project), no rationale I did not give you.
- **No invented tickets.** If I did not ask for it, there is no ticket. At most,
  leave a note clearly marked as yours.
- **No findings in the body.** If I ask you to investigate something, the request
  is the body. Your diagnosis does not replace it. Do not retitle "bus locations
  are broken" as "Handle timezone differences" — that title is your conclusion,
  not my ask.
- **No fabricated detail.** Never invent concrete examples, IDs, addresses, or
  numbers to make a ticket look fleshed out.
- **No label words in the prose.** "Patch", "Feature", "Bug" are Linear labels,
  not text to inline into the title or description.
- **No "if kept" bodies.** For a ticket that has no request behind it, leave the
  description empty and put your proposed wording in a comment.

## What TO produce

- **Body:** my request, restated declaratively, typos fixed, nothing added.
  Keep my own words where they carry meaning (e.g. "presentation oddities").
- **Label:** apply Patch / Feature / Bug / etc. as a Linear label.
- **Comment (optional):** implementation notes, findings, file and line pointers,
  as a Markdown blockquote under a single attribution line:

  ```
  Implementation notes (Claude, not part of the request)

  > ...the notes...
  ```

  Linear renders the blockquote, so my intent stays in the description and your
  analysis sits one level down, plainly labelled as yours.

## Examples

My own instruction, verbatim, which is the standing rule:

> I've been reading some of your tickets and they are too embellished. Don't
> stray too much from my request or word just log the title and a declarative
> rendition of my request.

A direct request with a label:

> For the stop specific screen we only really want to see from the bus location
> to the stop. Right now it's showing from the initial terminal to the bus to
> the stop. Log this as a patch.

becomes — description: "On the stop screen, show only the line from the bus
location to the stop. Right now it draws from the initial terminal, through the
bus, to the stop." — label: **Patch**. Nothing else.

An investigation request where the finding is yours:

> Can you investigate why showing bus locations may be broken in the app at the
> moment?

becomes — description: "Investigate why bus locations are broken in the app at
the moment, and fix it." — and the timezone root cause you found goes entirely in
a comment, not the body or the title.

## Style

- No em dashes.
- Declarative, plain, terse. Match my phrasing, do not improve on it.
