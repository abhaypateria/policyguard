# PolicyGuard

An AI compliance assistant for banks that answers questions **only** from the
organization's own policy documents — always citing the exact source file, page,
and document version, refusing to answer when the evidence is weak, and logging
every answer for audit.

> Built as a learning project to deeply understand **RAG (Retrieval-Augmented
> Generation)**, grounded LLM answering, and production concerns (auditability,
> versioning, evaluation) that a regulated bank would actually require.

## Why this exists (the problem)

Banks run on thousands of pages of internal policies + government regulations that
change often. An employee needs a correct answer in seconds ("what documents are
needed to open an NRI account?"). They can't just use a public chatbot because:

1. **Confidentiality** — policies aren't public; uploading them leaks data.
2. **Hallucination** — a made-up compliance answer becomes a regulatory fine.
3. **No audit trail** — regulators demand proof of *which* rule, *which* page,
   *which* version, on *what* date.
4. **No version awareness** — rules change; you often need the rule as it was on a
   past date.

PolicyGuard answers **only** from an approved corpus (public RBI master circulars
are used as the demo corpus), cites its sources, refuses when unsure, and keeps a
full audit log.

## Architecture

```
[React (JavaScript) UI] --HTTP/JSON--> [Spring Boot Backend] --> [PostgreSQL + pgvector]
                                              |
                                              +--> [LLM API (OpenAI / Gemini)]
```

**Ingestion:** Upload PDF -> extract text -> split into chunks -> embed each chunk
-> store vector + metadata (page, version, effective dates) in pgvector.

**Answering:** Question -> embed -> similarity search (top-K) -> **self-correction
loop** grades retrieval and retries/refuses -> grounded prompt -> LLM answer +
citations + confidence -> **audit log**.

## What makes this more than a generic doc chatbot

1. **Self-correction / refusal loop** — grades its own retrieval, rewrites weak
   queries, retries, and says *"Not found in the policy documents"* instead of
   guessing.
2. **Audit log + version awareness** — every answer is logged; queries can be
   filtered by document version / effective date.
3. **Evaluation harness** — measured before/after accuracy (plain RAG vs.
   self-correcting RAG). See [docs/EVALUATION.md](docs/EVALUATION.md).

## Tech stack

Java 21 · Spring Boot · Spring AI · PostgreSQL + pgvector (Docker Compose) ·
OpenAI/Gemini · React (JavaScript)

## Docs

- [7-day build plan](docs/PLAN.md)
- [System design explained simply](docs/SYSTEM_DESIGN.md)
- [Interview talking points](docs/TALKING_POINTS.md)
- [Evaluation results](docs/EVALUATION.md)

## Status

🚧 In active development — see [docs/PLAN.md](docs/PLAN.md) for progress.
