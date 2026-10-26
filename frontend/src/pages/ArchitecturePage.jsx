import { ArrowRight, CalendarClock, Fingerprint, Layers, Lock, RefreshCw, Scale, Search, ShieldCheck } from 'lucide-react';

const INGEST = ['Upload PDF', 'Parse page-by-page', 'Chunk (~200 tokens) + metadata', 'Embed locally (MiniLM, 384-d)', 'Store in pgvector'];
const ANSWER = ['Scope: versions in force on the date', 'Hybrid search (vectors + keywords, RRF)', 'Grade evidence; rewrite & retry', 'Refuse if still weak', 'Cited answer', 'Verify every claim', 'Seal in hash-chained audit log'];

function Flow({ steps, color }) {
  return (
    <div className="row" style={{ gap: 8 }}>
      {steps.map((s, i) => (
        <span key={s} className="row" style={{ gap: 8 }}>
          <span className="badge" style={{ background: 'var(--bg-2)', border: `1px solid ${color}`, color: 'var(--text)', padding: '7px 11px', fontWeight: 500 }}>{s}</span>
          {i < steps.length - 1 && <ArrowRight size={14} color="var(--faint)" />}
        </span>
      ))}
    </div>
  );
}

const PILLARS = [
  { icon: RefreshCw, title: 'Self-correcting retrieval', text: 'An LLM grader judges the evidence before any answer is written. Weak evidence triggers a rewritten query and a retry; still weak means an explicit refusal.' },
  { icon: CalendarClock, title: 'Temporal grounding', text: 'Every policy version has an in-force window. "As of" questions only search versions valid on that date, and superseded rules are flagged.' },
  { icon: Scale, title: 'Conflict detection', text: 'When an internal SOP disagrees with an RBI rule, both are shown and the regulator rule is marked as prevailing.' },
  { icon: Fingerprint, title: 'Tamper-evident audit', text: 'Each answer is hash-chained to the previous one (SHA-256). Editing or deleting any record breaks the chain and is detected.' },
  { icon: ShieldCheck, title: 'Claim verification', text: 'Each sentence of the answer is checked against the passage it cites; unsupported claims are flagged, and feed the confidence score.' },
  { icon: Lock, title: 'Confidential by design', text: 'Embeddings run inside the server; the LLM can be a fully local model (Ollama). Failover across Groq, Gemini and Ollama avoids quota outages.' },
];

const COMPARE = [
  ['Answers only from your policies', 'No - general training data', 'Yes'],
  ['Cites file, page and version', 'No', 'Yes, clickable to the PDF page'],
  ['Says "not found" instead of guessing', 'Rarely', 'Yes, calibrated threshold + grader'],
  ['Rule as it stood on a past date', 'No', 'Yes'],
  ['Flags internal-vs-regulator conflicts', 'No', 'Yes'],
  ['Audit trail a regulator can verify', 'No', 'Yes, hash-chained'],
  ['Confidential documents stay in-house', 'No - uploaded to a third party', 'Yes - local embeddings, optional local LLM'],
];

export default function ArchitecturePage() {
  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">How PolicyGuard works</h1>
          <p className="page-sub">React UI &rarr; Spring Boot (Controller &rarr; Service &rarr; Repository) &rarr; PostgreSQL + pgvector, with a failover chain of LLMs.</p>
        </div>
      </div>

      <div className="panel">
        <div className="panel-title"><Layers size={15} /> Ingestion (once per document version)</div>
        <Flow steps={INGEST} color="rgba(25,179,163,.5)" />
      </div>
      <div className="panel">
        <div className="panel-title"><Search size={15} /> Answering (every question)</div>
        <Flow steps={ANSWER} color="rgba(111,168,255,.5)" />
      </div>

      <div className="grid-3" style={{ marginTop: 16 }}>
        {PILLARS.map(({ icon: Icon, title, text }) => (
          <div className="panel" key={title} style={{ marginTop: 0 }}>
            <div className="row" style={{ marginBottom: 8 }}><Icon size={18} color="var(--teal)" /><b>{title}</b></div>
            <div className="small muted">{text}</div>
          </div>
        ))}
      </div>

      <div className="panel" style={{ marginTop: 16 }}>
        <div className="panel-title">Why not just use a public chatbot?</div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Requirement in a bank</th><th>Public chatbot</th><th>PolicyGuard</th></tr></thead>
            <tbody>
              {COMPARE.map(([req, bot, pg]) => (
                <tr key={req}><td>{req}</td><td className="muted">{bot}</td><td style={{ color: 'var(--teal)' }}>{pg}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="panel">
        <div className="panel-title">Stack</div>
        <div className="small muted">
          Java 21 &middot; Spring Boot 3.4 &middot; Spring AI 1.0 (PgVectorStore, TransformersEmbeddingModel, OpenAI-compatible chat) &middot;
          PostgreSQL + pgvector (HNSW) + full-text search &middot; Flyway &middot; React (Vite) &middot; Groq / Gemini / Ollama &middot;
          Hugging Face Spaces + Vercel + Neon.
        </div>
      </div>
    </>
  );
}
