# System Design

Each section: what the component does, how it works, and why it is built this way.

---

## 1. Layered architecture

```
Client (React)  ──HTTP/JSON──▶  Controller  →  Service  →  Repository  →  PostgreSQL + pgvector
```

- **Controllers** only translate HTTP to method calls (`ChatController`, `DocumentController`, `AuditController`,
  `EvalController`, `StatusController`).
- **Services** hold the logic (`RagService`, `RetrievalService`, `IndexingService`, `AuditService`…).
- **Repositories** are Spring Data JPA interfaces; the vector table is accessed through Spring AI's `VectorStore`.
- The schema is owned by **Flyway** migrations (`db/migration/V1__init.sql`), not Hibernate auto-DDL, so every
  environment gets an identical, versioned schema.

## 2. Ingestion pipeline

`upload → register (SHA-256, metadata, version chain) → async: parse → chunk → embed → store`

- **Parsing** — Spring AI `PagePdfDocumentReader` produces one document *per page*, so every chunk knows its page
  number and citations can deep-link to it. (Tika would lose page boundaries.)
- **Chunking** — `TokenTextSplitter`, ~200 tokens per chunk, plus a hard cap of 1,400 characters.
  *Why 200:* the embedding model reads at most ~256 word-pieces; anything longer is silently truncated before it is
  embedded. *Too big:* truncation and mixed topics. *Too small:* a rule loses its context. Each chunk gets a
  one-line **contextual header** (`title - version`) so an RBI rule and the bank's SOP on the same topic stay
  distinguishable for both search methods.
- **Metadata per chunk** — `documentId, title, issuer, sourceType, documentFamily, versionLabel, effectiveFrom,
  page, chunkIndex`.
- **Asynchronous** — indexing runs on a single background thread; the upload returns `202 Accepted` with status
  `PROCESSING`, and the UI polls until `INDEXED`.
- **Integrity** — the file's SHA-256 is stored: it blocks duplicate uploads and proves exactly which file an
  answer came from.

## 3. Embeddings and the vector store

- An **embedding** is a list of 384 numbers that represents the meaning of a text; texts with similar meaning have
  nearby vectors (measured with cosine similarity).
- Model: **all-MiniLM-L6-v2**, run **inside the JVM** through ONNX Runtime (Spring AI `TransformersEmbeddingModel`).
  No API key, no quota, no per-call cost, and document text never leaves the server for embedding.
- **pgvector** adds a `vector(384)` column type, cosine-distance operators and an **HNSW** index (approximate
  nearest-neighbour search) to PostgreSQL. Keeping vectors in the same database as documents and the audit log
  means one system to run, back up and secure.

## 4. Retrieval: hybrid search, top-K and threshold

- **Dense search** (pgvector) finds passages that *mean* the same thing.
- **Sparse search** (Postgres full-text, GIN index) finds passages that contain the same *words* — essential for
  regulatory terms embeddings blur: "Form 60", "CKYCR", "NRE", "V-CIP".
- **Reciprocal Rank Fusion** merges the two ranked lists: `score = Σ 1 / (60 + rank)`. It uses only ranks, so the
  very different score scales never need normalising.
- Both searches are restricted to the document versions **in force on the as-of date** (section 7).
- **top-K = 6** passages go to the LLM: enough evidence without distracting the model or slowing it down.
- **Similarity threshold τ = 0.30**: if even the best passage is less similar than τ, the evidence is too weak.
  τ is chosen from the threshold sweep in the evaluation (maximising correct refusals minus false refusals).

## 5. RAG vs fine-tuning

| | RAG (chosen) | Fine-tuning |
|---|---|---|
| Rules change | Upload the new version - live in minutes | Retrain |
| Citations | Exact file, page, version | Impossible - knowledge is inside the weights |
| Confidential data | Stays in our database | Baked into model weights |
| Refusal | "Not in the documents" is checkable | The model cannot know what it does not know |
| Cost | Runs on free tiers | GPU training |

## 6. Hallucination, grounding and the self-correction loop

- **Hallucination**: a fluent answer not supported by any source. **Grounding**: every claim traceable to a
  retrieved passage. Specifically we target *faithfulness* — staying true to the supplied context.
- Defences, in order:
  1. **Scope** — search only documents in force on the date.
  2. **Gate** — refuse early if the best similarity is below τ.
  3. **Grade** — an LLM grader returns JSON: `relevant`, `sufficient`, `missing`, `rewritten_query`, `conflicts`.
  4. **Rewrite & retry** — up to 2 more retrieval rounds with a rewritten, terminology-rich query; evidence from all
     rounds is pooled.
  5. **Refuse** — still insufficient → *"Not found in the policy documents… refer to Compliance."*
  6. **Strict generation** — temperature 0; answer only from labelled sources; cite every sentence; reply
     `INSUFFICIENT_EVIDENCE` if the answer is not there (the model's own abstention is honoured).
  7. **Verify** — each sentence's key terms are compared with the passage it cites: `SUPPORTED`, `WEAK`, `UNCITED`.
- **Robustness**: the grader's JSON is parsed leniently; if it is unreadable, the system *fails open* to the gate
  and the answer-level abstention instead of refusing everything. A business rule the model kept getting wrong —
  *conflicting sources are a finding, not insufficient evidence* — is enforced in code, not left to the prompt.
- **Confidence** = 35 % retrieval strength + 45 % groundedness + 20 % grader verdict − 5 % per retry. Every term is
  visible in the trace.

## 7. Versioning and temporal grounding

- A **document family** groups versions of one policy (`DEMOBANK-KYC-SOP`: `2023`, `2024`).
- Each version has `effective_from`; `effective_to` is derived: a version is in force until the next version of
  the family starts. The chain is recomputed on every upload or delete, so versions can arrive in any order.
- The validity windows live in **one place** (`policy_document`). At query time
  `findInForceAsOf(date)` returns the valid document ids, and retrieval filters chunks to those ids. Superseding a
  version is a single-row update — no chunk needs re-embedding.
- Answers built on a superseded version carry a notice: *in force from X, superseded on Y by Z*.

## 8. Conflict detection

The grader reports pairs of sources that state different values for the same rule. The answer prompt presents both
positions and states that the regulator's rule prevails over internal policy; the UI raises a red banner so
Compliance can fix the SOP. In the demo corpus, the bank's SOP says CKYCR upload within 15 days while the RBI
Master Direction requires 10 days.

## 9. Tamper-evident audit log

- Every question — answered, refused, or ungrounded comparison — is written to `audit_record` with the user, as-of
  date, mode, answer, confidence, cited sources, retrieved chunk ids, document versions, model and latency.
- **Hash chain**: `hash(n) = SHA-256(seq, fields…, prev_hash)` with `prev_hash = hash(n-1)`.
  Editing any field changes that record's hash; deleting a record leaves a sequence gap and a broken link.
  `GET /api/audit/verify` recomputes the whole chain and reports the first break.
- Appends are serialised by a lock held until the transaction commits, so concurrent requests cannot fork the chain.
- **Limitation**: deleting the newest records leaves a valid shorter chain. Mitigation: publish the head hash
  (`GET /api/audit/head`) outside the database — e-mail, ticket, or a write-once store.
- **Why a regulator cares**: it proves which rule, page and version an employee was shown, on what date, and that
  the record has not been altered since.

## 10. LLM failover and caching

- Groq, Gemini and Ollama all expose an OpenAI-compatible API, so one Spring AI `OpenAiChatModel` class serves all
  three. The gateway tries them in order; a failing provider (e.g. HTTP 429 rate limit) is put on a cooldown and
  the next one answers. A free-tier quota running out degrades speed, not availability.
- An exact-match answer cache (30-minute TTL) avoids repeat LLM calls and is cleared whenever the corpus changes.
  Every request is still audited.

## 11. Evaluation

44 labelled questions in four categories — answerable, temporal (answer depends on the date), conflict, and
unanswerable (the only correct behaviour is refusal). Each is answered by plain RAG and by PolicyGuard on the
**same pinned LLM**, so the difference isolates the self-correction loop. Reported: answer accuracy, retrieval hit
rate, correct-refusal and hallucination rates, false-refusal rate, temporal accuracy, conflict detection,
groundedness and latency, plus the threshold sweep. Results: [EVALUATION.md](EVALUATION.md).

## 12. Production concerns already handled

Secrets only via environment variables · Flyway migrations · Actuator health/metrics · global JSON error handling ·
request validation · Swagger/OpenAPI · CORS allow-list · per-IP rate limiting and a daily cap · admin key
(constant-time comparison) for uploads, deletes and evaluation · async ingestion · unit and live-model tests ·
single-container Docker build with the embedding model baked in.

## 13. Scaling to 10,000 employees

- **Stateless app tier** behind a load balancer; the audit lock moves to a database advisory lock (or a single
  append service) so the chain stays linear across instances.
- **Database**: read replicas for retrieval; partition the audit table by month; periodic external anchoring of the
  chain head.
- **Ingestion** through a job queue with workers; re-index only changed documents.
- **Retrieval quality**: add a cross-encoder re-ranker on the top ~30 candidates; a semantic cache for frequent
  questions.
- **Access control**: SSO (OIDC) for real user identities; per-department document permissions applied as an extra
  retrieval filter (multi-tenancy).
- **Observability**: tracing per pipeline step, dashboards for refusal rate, groundedness and provider failovers;
  alerts when refusal rates jump (a sign of a missing or broken document).
