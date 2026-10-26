# Setup & Deployment Guide

Everything here is **free** and needs **no credit card**.

| Piece | Service | Why this one |
|---|---|---|
| Database (Postgres + pgvector) | **Neon** | Free serverless Postgres with pgvector built in; same database for dev and prod |
| LLM (primary) | **Groq** | Free API key, very fast Llama models |
| LLM (fallback) | **Google Gemini** | Free API key; used automatically if Groq is rate-limited |
| LLM (offline fallback) | **Ollama** (local) | Runs on your own laptop/GPU; no internet, no quota |
| Backend hosting | **Hugging Face Spaces** (Docker) | Free tier gives 16 GB RAM - enough for the in-JVM embedding model |
| Frontend hosting | **Vercel** | Free static hosting for the React app |

---

## Step 1 - Accounts and keys (about 15 minutes)

> You do these steps yourself in your own browser. Never paste keys into chat; they go only into
> your local `.env` file and the hosting providers' secret settings.

### 1a. Neon (database)
1. Go to https://neon.tech -> **Sign up with GitHub**.
2. Create a project: name `policyguard`, Postgres version 16 or 17, region **AWS Asia Pacific (Singapore)** (closest to India).
3. On the project dashboard click **Connect**. Turn **OFF "Connection pooling"**, then copy the connection string
   (`postgresql://...neon.tech/neondb?sslmode=require`).

### 1b. Groq (fast free LLM)
1. Go to https://console.groq.com -> sign in -> **API Keys** -> **Create API Key** -> copy it.

### 1c. Google Gemini (fallback LLM)
1. Go to https://aistudio.google.com -> **Get API key** -> **Create API key** -> copy it.

### 1d. Put them in `.env`
In the project folder `C:\Users\abhay\policyguard`, copy `.env.example` to a new file named `.env` and paste:
```
DATABASE_URL=<the Neon string>
GROQ_API_KEY=<your Groq key>
GEMINI_API_KEY=<your Gemini key>
```

### 1e. (For deployment) Hugging Face and Vercel
1. https://huggingface.co -> **Sign up**. Then **Settings -> Access Tokens -> Create new token** (type **Write**). Keep it handy.
2. https://vercel.com -> **Sign up with GitHub**.

---

## Step 2 - Run locally

```powershell
cd C:\Users\abhay\policyguard
.\mvnw.cmd spring-boot:run
```
On first start Flyway creates the tables, the bundled corpus is indexed in the background
(about 1-2 minutes), and the API is at http://localhost:8081 (Swagger UI at `/swagger-ui.html`).

Frontend (separate terminal):
```powershell
cd C:\Users\abhay\policyguard\frontend
npm install
npm run dev
```
Open http://localhost:5173.

---

## Step 3 - Deploy

### Backend (+ bundled UI) -> Hugging Face Space
1. Log in once from your own terminal (paste your **Write** token when asked):
   ```powershell
   pip install huggingface_hub
   hf auth login
   ```
2. Upload the code (creates the Space if needed):
   ```powershell
   python deploy\deploy_hf.py --space <your-hf-username>/policyguard
   ```
3. In the Space page: **Settings -> Variables and secrets**
   - **Secrets**: `DATABASE_URL`, `GROQ_API_KEY`, `GEMINI_API_KEY`, `ADMIN_KEY`
   - **Variables**: `EVAL_ENABLED=false`, `CORS_ORIGINS=*` (tighten to your Vercel URL later)
4. The Space builds the Docker image in the cloud (about 5-8 minutes). The whole app - UI and API - is then live at
   `https://<user>-policyguard.hf.space`. The Space uses the same Neon database, so the corpus, audit log and
   evaluation results you created locally are already there.

### Frontend -> Vercel
```powershell
cd C:\Users\abhay\policyguard\frontend
npx vercel login
npx vercel --prod --build-env VITE_API_BASE=https://<user>-policyguard.hf.space
```
Then set `CORS_ORIGINS=https://<your-app>.vercel.app` in the Space variables.

### Before the review
Free Spaces sleep after ~48 h without traffic. Open the Space URL ~5 minutes before presenting so it is awake,
and keep the local version (`mvnw spring-boot:run` + Ollama) ready as an offline backup.
