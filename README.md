# PolicyGuard 🛡️

**An AI compliance assistant for banks** that answers questions **only** from the
organization's own policy documents — always citing the exact source file, page, and
document version, **refusing to answer when the evidence is weak**, and logging every
answer for audit.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.13-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-blue)
![Spring AI](https://img.shields.io/badge/Spring%20AI-RAG-purple)
![Status](https://img.shields.io/badge/status-in%20development-yellow)

> Built to deeply understand **Retrieval-Augmented Generation (RAG)**, grounded LLM
> answering, and the production concerns a regulated bank actually requires —
> auditability, document versioning, and measured evaluation.

---

## The problem

Banks run on thousands of pages of internal policies + government regulations that
change often. A branch employee needs a correct answer in seconds — *"what documents
are needed to open an NRI account?"* — but today they either dig through a 500-page PDF
for 40 minutes or wait days for the compliance team. Getting it wrong means regulatory
fines and lawsuits.

They **can't just use a public chatbot** because:

| Risk | Why it matters |
|------|----------------|
| 🔒 **Confidentiality** | Policies aren't public; uploading them to ChatGPT leaks data |
| 🤖 **Hallucination** | A made-up compliance answer becomes a regulatory fine |
| 📜 **No audit trail** | Regulators demand proof of *which* rule, *which* page, *which* version, on *what* date |
| 🗓️ **No version awareness** | Rules change; you often need the rule as it was on a past date |

**PolicyGuard** answers only from an approved corpus (public RBI master circulars are
used as the demo corpus), cites its sources, refuses when unsure, and keeps a full audit log.

---

## What makes this more than a generic doc chatbot

Most tutorial RAG apps just do *upload → ask → answer*. PolicyGuard adds the three
things that make it a **compliance** tool a bank would actually consider:

1. **🔄 Self-correction / refusal loop** — the system grades its own retrieval, rewrites
   weak queries and retries, and says *"Not found in the policy documents"* instead of
   guessing.
2. **📋 Audit log + version awareness** — every answer is logged (question, sources,
   confidence, user, timestamp, document version); queries can be filtered by version /
   effective date.
3. **📊 Evaluation harness** — measured before/after accuracy (plain RAG vs.
   self-correcting RAG). See [docs/EVALUATION.md](docs/EVALUATION.md).

---

## Architecture

```
┌─────────────────────┐   HTTP / JSON   ┌──────────────────────────────┐
│  React (JavaScript) │ ───────────────▶│      Spring Boot Backend     │
│         UI          │                 │  Controller → Service → Repo │
└─────────────────────┘                 └───────────────┬──────────────┘
                                                         │
                                   ┌─────────────────────┼──────────────────────┐
                                   ▼                                            ▼
                     ┌──────────────────────────┐               ┌──────────────────────────┐
                     │  PostgreSQL + pgvector   │               │   LLM API (OpenAI/Gemini)│
                     │  (documents + vectors +  │               │   embeddings + answers   │
                     │   audit log)             │               └──────────────────────────┘
                     └──────────────────────────┘
```

**Ingestion (once per document):** Upload PDF → extract text → split into chunks →
embed each chunk → store vector + metadata (page, version, effective dates) in pgvector.

**Answering (every question):** Question → embed → similarity search (top-K) →
**self-correction loop** grades retrieval and retries/refuses → grounded prompt → LLM
answer + citations + confidence → **audit log**.

---

## Tech stack

| Layer | Technology |
|-------|------------|
| Language | Java 21 |
| Backend | Spring Boot 3.4.13 (Web, Data JPA, Validation) |
| AI / RAG | Spring AI (embeddings, ChatClient, VectorStore) |
| Database | PostgreSQL 16 + pgvector extension |
| LLM | OpenAI / Gemini |
| Frontend | React (JavaScript) |
| Infra | Docker Compose · Maven wrapper |

---

## Quick start

**Prerequisites:** Java 21, Docker Desktop, (Node 18+ for the frontend later).

> ⚠️ **Order matters.** The backend connects to Postgres at startup, so the database
> must be running **first**: Docker Desktop → Postgres → Spring Boot.

```bash
# 1. Start Docker Desktop manually and wait for "Engine running"

# 2. Start PostgreSQL (pgvector) in the background
docker compose up -d

# 3. Confirm the DB is healthy
docker compose ps

# 4. Start the backend (Windows — uses the Maven wrapper, no global Maven needed)
.\mvnw.cmd spring-boot:run
```

Then check it's alive:

```bash
curl http://localhost:8080/health
# -> PolicyGuard is running
```

Stop with `Ctrl+C`, then `docker compose down`.

---

## Project structure

```
policyguard/
├─ pom.xml                        # Maven build config (Java 21, Spring Boot 3.4.13)
├─ docker-compose.yml             # PostgreSQL + pgvector (host port 5433)
├─ mvnw / mvnw.cmd / .mvn/        # Maven wrapper
├─ src/main/java/com/policyguard/
│  ├─ PolicyGuardApplication.java # entry point
│  ├─ controller/                 # HTTP layer  (Service / Repository / entity / dto come next)
│  └─ ...
├─ src/main/resources/
│  └─ application.properties      # DB + JPA config
└─ docs/                          # build logs, system design, talking points, evaluation
```

---

## Roadmap

Built section by section over a focused 7-day plan (see [docs/PLAN.md](docs/PLAN.md)):

- [x] **§1 Foundation** — Spring Boot skeleton, Docker Postgres+pgvector, `/health`
- [ ] **§2 Document upload & parsing** — upload endpoint, text extraction, metadata
- [ ] **§3 Chunking + embedding + vector store**
- [ ] **§4 Retrieval** — similarity search, top-K, threshold
- [ ] **§5 RAG answering + citations**
- [ ] **§6 Self-correction loop** *(differentiator)*
- [ ] **§7 Audit log + versioning** *(differentiator)*
- [ ] **§8 Evaluation harness** *(differentiator)*
- [ ] **§9 React frontend**
- [ ] **§10 Deploy + docs**

---

## Documentation

- 📅 [7-day build plan](docs/PLAN.md)
- 🧠 [System design, explained simply](docs/SYSTEM_DESIGN.md)
- 🎤 [Interview talking points](docs/TALKING_POINTS.md)
- 📊 [Evaluation results](docs/EVALUATION.md)
- 📝 [Daily build logs](docs/)

---

*This is a learning project built to be understood and defended end-to-end, not a
black box. Each design decision — and each deliberate difference from the tutorial it
follows — is documented in the daily build logs.*
