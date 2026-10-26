import { useEffect, useRef, useState } from 'react';
import { Loader2, Send } from 'lucide-react';
import { api, storage } from '../api.js';
import AnswerCard from '../components/AnswerCard.jsx';
import Sources from '../components/Sources.jsx';
import Trace from '../components/Trace.jsx';

// The live-demo script: each chip shows off one capability.
const SAMPLES = [
  { tag: 'NRI', color: 'blue', q: 'What documents does an NRI need to open an NRE account at Demo Bank?' },
  { tag: 'CONFLICT', color: 'red', q: "Within how many days must a new customer's KYC records be uploaded to the CKYCR?" },
  { tag: 'AS OF 2023', color: 'amber', q: 'What is the minimum initial deposit for an NRE savings account at Demo Bank?', asOf: '2023-10-01' },
  { tag: 'TODAY', color: 'teal', q: 'What is the minimum initial deposit for an NRE savings account at Demo Bank?' },
  { tag: 'AS OF 2023', color: 'amber', q: 'Can Demo Bank open savings accounts through video-based KYC (V-CIP)?', asOf: '2023-10-01' },
  { tag: 'REFUSAL', color: 'violet', q: "What is Demo Bank's policy on cryptocurrency custody for customers?" },
  { tag: 'RBI', color: 'blue', q: 'What limits apply to a Small Account?' },
  { tag: 'CONFLICT', color: 'red', q: 'Can a demand draft of Rs. 75,000 be issued against cash?' },
];

const USERS = ['branch-officer-01', 'nri-desk-02', 'compliance-analyst', 'internal-auditor'];
const PHASES = ['Scoping policy versions in force', 'Hybrid search: meaning + keywords', 'Grading the evidence', 'Writing a cited answer', 'Verifying every claim', 'Sealing into the audit log'];
const today = () => new Date().toISOString().slice(0, 10);

export default function AskPage() {
  const [question, setQuestion] = useState('');
  const [asOf, setAsOf] = useState(today());
  const [mode, setMode] = useState('SELF_CORRECTING');
  const [compare, setCompare] = useState(false);
  const [user, setUser] = useState(storage.get('pg.user', USERS[0]));
  const [loading, setLoading] = useState(false);
  const [phase, setPhase] = useState(0);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [flash, setFlash] = useState(null);
  const timer = useRef(null);

  useEffect(() => storage.set('pg.user', user), [user]);
  useEffect(() => () => clearInterval(timer.current), []);

  const ask = async (q = question, date = asOf, withCompare = compare) => {
    if (!q.trim() || loading) return;
    setLoading(true);
    setError('');
    setResult(null);
    setPhase(0);
    timer.current = setInterval(() => setPhase((p) => Math.min(p + 1, PHASES.length - 1)), 1400);
    const payload = { question: q.trim(), asOfDate: date === today() ? null : date, mode };
    try {
      const data = withCompare ? await api.compare(payload, user) : await api.ask(payload, user);
      setResult(withCompare ? { compare: true, ...data } : { compare: false, single: data });
    } catch (e) {
      setError(e.message);
    } finally {
      clearInterval(timer.current);
      setLoading(false);
    }
  };

  const useSample = (s) => {
    const date = s.asOf || today();
    setQuestion(s.q);
    setAsOf(date);
    ask(s.q, date, compare);
  };

  const cite = (label) => {
    const el = document.getElementById('src-' + label);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setFlash(label);
    setTimeout(() => setFlash(null), 1300);
  };

  const main = result && (result.compare ? result.policyGuard : result.single);

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Ask a compliance question</h1>
          <p className="page-sub">
            Answers come <b>only</b> from the bank&rsquo;s indexed policies, cite the exact page and version, say
            &ldquo;not found&rdquo; instead of guessing, and are sealed into a tamper-evident audit log.
          </p>
        </div>
      </div>

      <div className="panel">
        <textarea
          className="input"
          placeholder="e.g. What documents does an NRI need to open an NRE account?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && (e.ctrlKey || e.metaKey) && ask()}
        />
        <div className="row" style={{ marginTop: 12 }}>
          <div>
            <span className="label">Rules in force as of</span>
            <input type="date" className="input" value={asOf} max={today()} onChange={(e) => setAsOf(e.target.value || today())} />
          </div>
          <div>
            <span className="label">Pipeline</span>
            <div className="seg">
              <button className={mode === 'SELF_CORRECTING' ? 'on' : ''} onClick={() => setMode('SELF_CORRECTING')}>Self-correcting</button>
              <button className={mode === 'PLAIN' ? 'on' : ''} onClick={() => setMode('PLAIN')}>Plain RAG</button>
            </div>
          </div>
          <div>
            <span className="label">Asking as</span>
            <select className="select input" value={user} onChange={(e) => setUser(e.target.value)}>
              {USERS.map((u) => <option key={u}>{u}</option>)}
            </select>
          </div>
          <label className="toggle" style={{ marginTop: 18 }}>
            <input type="checkbox" checked={compare} onChange={(e) => setCompare(e.target.checked)} />
            Compare with a plain chatbot
          </label>
          <div className="grow" />
          <button className="btn primary" style={{ marginTop: 18 }} disabled={loading || !question.trim()} onClick={() => ask()}>
            {loading ? <Loader2 size={16} className="spin" /> : <Send size={16} />} Ask
          </button>
        </div>
        <div style={{ marginTop: 16 }}>
          <span className="label">Try the demo questions</span>
          <div className="chips">
            {SAMPLES.map((s, i) => (
              <button key={i} className="chip" onClick={() => useSample(s)} disabled={loading}>
                <span className={'tag badge ' + s.color}>{s.tag}</span>
                {s.q}
              </button>
            ))}
          </div>
        </div>
      </div>

      {loading && (
        <div className="panel" style={{ marginTop: 18 }}>
          <div className="row"><Loader2 size={18} className="spin" color="var(--teal)" /> <b>{PHASES[phase]}&hellip;</b></div>
          <div className="skeleton" style={{ width: '92%' }} />
          <div className="skeleton" style={{ width: '78%' }} />
          <div className="skeleton" style={{ width: '64%' }} />
        </div>
      )}
      {error && <div className="panel err" style={{ marginTop: 18 }}>{error}</div>}

      {main && !result.compare && (
        <div className="answer-grid">
          <div>
            <AnswerCard r={main} onCite={cite} />
            <div style={{ marginTop: 16 }}><Trace steps={main.trace} /></div>
          </div>
          <Sources sources={main.sources} flash={flash} refused={main.outcome === 'REFUSED'} />
        </div>
      )}

      {main && result.compare && (
        <>
          <div className="compare-grid">
            <AnswerCard r={result.policyGuard} onCite={cite} title="PolicyGuard (grounded, self-correcting)" />
            <AnswerCard r={result.ungrounded} title="Plain chatbot (same LLM, no documents)" />
          </div>
          <div className="answer-grid">
            <Trace steps={result.policyGuard.trace} />
            <Sources sources={result.policyGuard.sources} flash={flash} refused={result.policyGuard.outcome === 'REFUSED'} />
          </div>
        </>
      )}
    </>
  );
}
