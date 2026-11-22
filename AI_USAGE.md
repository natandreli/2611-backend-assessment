# AI usage disclosure

- [ ] I did not use any AI tool in this assessment.

I used one AI tool throughout the assessment as a pair-programming assistant. I made the design decisions and reviewed every output before accepting it; the tool drafted text and code, challenged my choices and ran the validations.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| Claude Code (Anthropic), CLI, model Claude Opus 5.5 | Part A: `docs/design/SOLUTION_DESIGN.md`, `docs/adr/`, `docs/ASSUMPTIONS.md` | Discussing alternatives and trade-offs, drafting the text and Mermaid diagrams for the decisions I made, checking each section against `TASKS.md` and the references |
| Same | Part B: `docs/api/`, `practical/` | Drafting the contracts, the Java code and the tests; running the tests and the validators |
| Same | Part C: `infra/`, `docs/infra/AZURE_SERVICES.md`, `.github/`, `practical/Dockerfile` | Drafting the Bicep modules, the pipelines and the cost estimate; validating them offline |
| Same | Parts D and E: `docs/operations/`, `pr-review/REVIEW.md` | Analyzing the incident data and the PR diff, drafting the documents |
| Same | Part F: this file, `docs/ai/AI_GUIDELINES.md` | First draft; I reviewed and edited it |

## Validation

Nothing was accepted because it read well; each output was checked against something outside the AI:

- **Design (Part A).** I reviewed `SOLUTION_DESIGN.md` section by section, then each ADR and the assumptions, then all of them together. Each review checked the text against `TASKS.md`, `SCENARIO.md` and `reference/` (provider specs, Salesforce constraints, the incident data). Figures were recalculated: volumes, API budget and freshness during outages. I looked at the rendered Mermaid diagrams in VS Code, including the dark theme, and asked for changes when they were hard to read.
- **Contracts (Part B).** The OpenAPI file passes the official OpenAPI 3.1 validator. The JSON Schema was checked against its meta-schema, its examples validate, and a set of invalid events (wrong decimals, extra personal-data field, offset time, refund rules) is rejected.
- **Code (Part B).** 75 JUnit tests run on every change. The signature tests use the providers' official test vectors from `reference/providers/`, not values produced by the AI. The image was built with Docker and started; unsigned requests got `401`.
- **Infrastructure and pipelines (Part C).** `az bicep lint` and `az bicep build` with no errors or warnings, and the three parameter files build. The workflows pass `actionlint`.
- **Operations (Part D).** The incident figures were recalculated from `reference/incident/` (4,312 dead-lettered messages × 10 deliveries = 43,120 `400` responses; API budget from 38% to 66%), and each piece of evidence for the root cause was traced to the incident data.
- **Review (Part E).** A script checked that every `file:line` in `REVIEW.md` points to the cited code on the `+` side of the diff.
- **Coherence.** After each part, all parts were reviewed together, which found differences between the design and the code that were then fixed.

## What I discarded or corrected

- **Content I had not asked for.** The first data model diagram added invented content and did not follow ER conventions. I asked for it to follow the format of a draw.io example I provided, and for decisions to be proposed and agreed before being written.
- **Data model.** A three-table model was proposed. After I asked why each table and column was needed, it became two tables, and the intake was made to record every notification.
- **A status that could never be set.** The design marked notifications as `DUPLICATE`, but Service Bus discards duplicates silently, so nothing could ever set it. The intake now looks up the event ID before publishing.
- **Wrong or unsupported statements**, found in reviews and removed or corrected:
  - Mermaid renders attributes in a fixed order;
  - the executor in PR-214 had no shutdown handling;
  - "a request takes a few milliseconds", which was never measured.
- **An over-correction.** The AI removed "ten times in two seconds" from the design as unsupported; later the consumer log in the incident showed about 2.5 seconds. The current wording ("immediately, up to 10 times") is accurate.
- **Bugs found by reviews and tests**, all fixed with tests or validation:
  - correlation IDs longer than the column, or with line breaks that could forge log lines;
  - identifiers of parked notifications longer than their column;
  - amounts above Salesforce's `Currency(16, 2)`;
  - telemetry configured without the identity's client ID;
  - a CD pipeline that would have deployed the in-memory stubs, now documented as not deployable.
- **Line endings.** The official signature vectors failed because Git on Windows had converted the samples to CRLF. The fix (`.gitattributes`) keeps them byte-exact; the code was not changed to fit the data.

## Data I shared with AI tools

- The contents of this repository, including the scenario documents and the fictional sandbox secrets and sample payloads published in `reference/` for testing.
- Screenshots of rendered diagrams and of a draw.io diagram example.
- No real secrets, credentials or personal data, and no material from any current or former employer.
