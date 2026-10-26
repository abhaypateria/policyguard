# Running PolicyGuard

| Piece | Service | Notes |
|---|---|---|
| Database (Postgres + pgvector) | **Neon** (free) | Serverless Postgres; the same database for every run |
| LLM | **Groq** (free) | `openai/gpt-oss-120b`, with `gpt-oss-20b` as a fast second model |
| LLM fallbacks | Gemini (optional key), **Ollama** (local, offline) | Tried automatically if Groq is unavailable |
| Embeddings | all-MiniLM-L6-v2, **inside the app** | No key, no quota |

## 1. One-time setup

1. **Neon** - https://neon.tech -> create a project (region Singapore) -> **Connect** -> turn **off** "Connection pooling" -> copy the `postgresql://...` string.
2. **Groq** - https://console.groq.com/keys -> **Create API Key**.
3. Copy `.env.example` to `.env` and fill in `DATABASE_URL` and `GROQ_API_KEY`. (`.env` is gitignored.)
4. Prerequisites: Java 21 and Node 18+.

## 2. Start it

```powershell
.\start-demo.ps1
```

This builds the React UI into the backend and starts one process. Open **http://localhost:8081**.
On the very first start, Flyway creates the schema and the bundled corpus is indexed (about 1 minute).

Useful flags: `-SkipUiBuild` (faster restart), `-WithOllama` (also start the local offline model).

Developer mode (hot-reloading UI): run `.\mvnw.cmd spring-boot:run`, then `cd frontend; npm run dev` and open http://localhost:5173.

## 3. Share a temporary public link (optional)

```powershell
.\share-link.ps1
```

Creates a free Cloudflare quick tunnel (no account) and prints an `https://….trycloudflare.com` address that
reaches the app on this laptop for as long as the window stays open. It refuses to run unless `ADMIN_KEY` is set
in `.env`, so uploads, deletes and evaluation runs stay protected while the link is public.

## 4. Before a live demo

- Disconnect any **VPN**: it can stall the database connection (the TCP connection opens but the TLS handshake never completes).
- Test once on the **venue's Wi-Fi** - some networks block the Postgres port. Keep a **phone hotspot** as backup.
- Open the app 2 minutes early: Neon suspends idle databases and the first query wakes it.
- Ask the demo questions once beforehand; repeated questions are served from the 30-minute cache.

## 5. Hosting it permanently

`Dockerfile` builds a single container (UI + API). It needs **at least 1 GB of RAM**: the in-JVM embedding
model needs about 300 MB of heap, and a 512 MB free tier was measured to run out of memory. Suitable hosts:
Azure for Students (free credit), Google Cloud Run, or any VM. Set `DATABASE_URL`, `GROQ_API_KEY`, `ADMIN_KEY` and
`CORS_ORIGINS` as environment variables.
