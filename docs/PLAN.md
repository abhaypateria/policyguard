# PolicyGuard — 7-Day Build Plan

Each day builds specific sections. A section must **work AND be understood** before
the next. If we slip, we cut frontend polish — **never** the eval harness or deploy.

| Day | Sections | Deliverable |
|-----|----------|-------------|
| 1 | §1 Foundation + start §2 Upload | Spring Boot runs; Postgres+pgvector in Docker; `/health` responds; PDF upload saves |
| 2 | finish §2 + §3 Chunk/Embed/Store | Full ingestion pipeline: parse → chunk → embed → store in pgvector w/ metadata |
| 3 | §4 Retrieval + §5 RAG answer | Question → top-K chunks → grounded answer + citations + confidence |
| 4 | §6 Self-correction loop **[DIFF 1]** | Grades retrieval, rewrites, retries, refuses on weak evidence |
| 5 | §7 Audit + versioning **[DIFF 2]** + start §8 | Every answer logged; version/effective-date filtering |
| 6 | finish §8 Eval **[DIFF 3]** + §9 React UI | ~20 Q/A test set; plain vs self-correcting numbers; upload+chat+citations UI |
| 7 | §10 Deploy + README + diagram + buffer | `docker-compose up` runs everything; final interview drilling |

## The 10 build sections

1. **Foundation** — Spring Boot skeleton, docker-compose (Postgres + pgvector), first endpoint
2. **Document upload & parsing** — upload endpoint, text extraction, DocumentMetadata entity + repo
3. **Chunking + embedding + vector store** — chunk, enrich metadata, embed, store in pgvector
4. **Retrieval** — similarity search, top-K, similarity threshold
5. **RAG answering + citations** — grounded prompt, answer + citations + confidence
6. **Self-correction loop** [DIFF 1] — grade retrieval, rewrite, retry, refuse
7. **Audit log + versioning** [DIFF 2] — audit entity, version/effective-date filtering
8. **Eval harness** [DIFF 3] — test set, plain vs self-correcting, EVALUATION.md numbers
9. **React (JS) frontend** — upload page, chat with citations + confidence, audit view
10. **Deploy + README + architecture diagram**

## Progress log

- [ ] Day 0 — repo created, environment verified (Java 21, Docker, Node ✓)
- [ ] Day 1
- [ ] Day 2
- [ ] Day 3
- [ ] Day 4
- [ ] Day 5
- [ ] Day 6
- [ ] Day 7
