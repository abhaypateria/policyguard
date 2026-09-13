# System Design — explained simply

> Plain-language explanations of every concept, written so I can defend them in an
> interview. Filled in as we build. **No section is added until we've actually built it.**

## Day 1 — Foundation concepts

### The layered architecture (the shape of the whole app)
Requests flow through clear layers, each with one job:

```
Client (browser / React)
   │  HTTP + JSON
   ▼
Controller   ← receives requests, returns responses
   ▼
Service      ← business logic (added later)
   ▼
Repository   ← database access (added later)
   ▼
PostgreSQL + pgvector
```

Why layer it? Each layer has a single responsibility, so it's easy to understand, test,
and change one part without breaking the others. On Day 1 we only have the Controller.

### How a request is served (embedded server + Spring MVC)
The app contains its own web server (**embedded Tomcat**). When a request comes in,
Spring MVC's **DispatcherServlet** looks at the URL and method (e.g. `GET /health`) and
routes it to the matching controller method. The method's return value becomes the HTTP
response.

### How the app talks to the database (JDBC → JPA → Hibernate)
- **JDBC** is the low-level Java way to run SQL against a database.
- **JPA** is a higher-level standard: I work with Java objects, it handles the SQL.
- **Hibernate** is the library that implements JPA.
- The **datasource** (URL + username + password in `application.properties`) is the
  configured connection Spring uses to reach Postgres.

### Why Docker for the database
The database runs in a **container** from the **pgvector image**, started by Docker
Compose. This gives me a real Postgres identical to production, without installing it,
and it's reproducible on any machine.

### Key Day-1 design choices
- **Port 5433** on the host (mapped to 5432 in the container) to dodge any local Postgres.
- **pgvector image from the start** so we never swap databases when vectors arrive.
- **Pinned Spring Boot 3.4.x** for video-parity and Spring AI 1.0 compatibility.

---

## Placeholders (added on the day we build them)

### Chunking
_TBD (Section 3)_

### Embeddings
_TBD (Section 3)_

### Vector Search
_TBD (Sections 3–4)_

### RAG (Retrieval-Augmented Generation)
_TBD (Section 5)_

### Self-Correction Loop
_TBD (Section 6)_

### Audit Logging
_TBD (Section 7)_

### Versioning
_TBD (Section 7)_

### Evaluation
_TBD (Section 8)_

### Scaling
_TBD (hybrid search + reranking, caching, async ingestion, multi-tenancy, observability)_
