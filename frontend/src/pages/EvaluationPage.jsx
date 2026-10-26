import { useCallback, useEffect, useState } from 'react';
import { FlaskConical, Loader2, Play } from 'lucide-react';
import { api, storage } from '../api.js';

const METRICS = [
  ['overallCorrect', 'Overall correct behaviour', false],
  ['answerAccuracy', 'Answer accuracy (answerable)', false],
  ['correctRefusalRate', 'Correct refusal (unanswerable)', false],
  ['hallucinationRate', 'Hallucination rate (unanswerable)', true],
  ['falseRefusalRate', 'False refusal (answerable)', true],
  ['temporalAccuracy', 'Temporal accuracy (as-of date)', false],
  ['conflictDetectionRate', 'Conflict detection', false],
  ['retrievalHitRate', 'Retrieval hit rate', false],
];

/** Correct-refusal vs false-refusal as the abstention threshold moves - how the threshold was calibrated. */
function SweepChart({ rows, current }) {
  const W = 560, H = 230, P = 36;
  const xs = rows.map((r) => r.threshold);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const x = (v) => P + ((v - minX) / (maxX - minX || 1)) * (W - 2 * P);
  const y = (v) => H - P - (v / 100) * (H - 2 * P);
  const line = (key) => rows.map((r) => `${x(r.threshold)},${y(r[key])}`).join(' ');
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', maxWidth: W }}>
      {[0, 25, 50, 75, 100].map((t) => (
        <g key={t}>
          <line x1={P} x2={W - P} y1={y(t)} y2={y(t)} stroke="#233a61" strokeDasharray="3 4" />
          <text x={P - 8} y={y(t) + 4} fill="#6c7f9f" fontSize="10" textAnchor="end">{t}%</text>
        </g>
      ))}
      {xs.map((t) => <text key={t} x={x(t)} y={H - P + 16} fill="#6c7f9f" fontSize="10" textAnchor="middle">{t.toFixed(2)}</text>)}
      {current != null && current >= minX && current <= maxX && (
        <g>
          <line x1={x(current)} x2={x(current)} y1={P - 6} y2={H - P} stroke="#f0b44c" strokeDasharray="4 3" />
          <text x={x(current)} y={P - 10} fill="#f0b44c" fontSize="10" textAnchor="middle">configured {current}</text>
        </g>
      )}
      <polyline points={line('correctRefusal')} fill="none" stroke="#19b3a3" strokeWidth="2.5" />
      <polyline points={line('falseRefusal')} fill="none" stroke="#ef6b6b" strokeWidth="2.5" />
      <text x={W - P} y={P - 18} fill="#19b3a3" fontSize="11" textAnchor="end">&#9632; correct refusals (unanswerable)</text>
      <text x={W - P} y={P - 4} fill="#ef6b6b" fontSize="11" textAnchor="end">&#9632; false refusals (answerable)</text>
    </svg>
  );
}

export default function EvaluationPage() {
  const [run, setRun] = useState(null);
  const [current, setCurrent] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [provider, setProvider] = useState('ollama');
  const [adminKey, setAdminKey] = useState(storage.get('pg.adminKey'));

  const load = useCallback(() => {
    api.evalLatest().then(setRun).catch((e) => (e.status === 404 ? setRun(false) : setError(e.message)));
    api.evalCurrent().then(setCurrent).catch(() => {});
  }, []);

  useEffect(() => {
    load();
    api.status().then(setStatus).catch(() => {});
  }, [load]);

  useEffect(() => {
    if (!current || current.status !== 'RUNNING') return;
    const t = setInterval(() => api.evalCurrent().then((c) => (setCurrent(c), c.status !== 'RUNNING' && load())), 5000);
    return () => clearInterval(t);
  }, [current, load]);

  const start = async () => {
    setError('');
    try {
      storage.set('pg.adminKey', adminKey);
      await api.runEval(provider === 'auto' ? '' : provider, adminKey);
      setCurrent({ status: 'RUNNING' });
    } catch (e) {
      setError(e.message);
    }
  };

  const s = run && run.summary;
  const byId = {};
  (run && run.details ? run.details : []).forEach((d) => ((byId[d.id] = byId[d.id] || { id: d.id, category: d.category })[d.mode] = d));

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Evaluation: plain RAG vs PolicyGuard</h1>
          <p className="page-sub">
            Every gold question is answered by both pipelines on the <b>same LLM</b>. Unanswerable questions have only one
            correct behaviour &mdash; refusing. This is measured evidence, not a claim.
          </p>
        </div>
      </div>
      {error && <div className="panel err" style={{ marginBottom: 16 }}>{error}</div>}

      {run === false && <div className="panel muted">No evaluation has been run yet.</div>}

      {s && (
        <>
          <div className="grid-3" style={{ marginBottom: 16 }}>
            <div className="panel">
              <div className="stat" style={{ color: 'var(--teal)' }}>{s.SELF_CORRECTING.overallCorrect}%</div>
              <div className="stat-label">PolicyGuard overall correct behaviour <span className="faint">(plain RAG {s.PLAIN.overallCorrect}%)</span></div>
            </div>
            <div className="panel">
              <div className="stat" style={{ color: 'var(--red)' }}>{s.PLAIN.hallucinationRate}% &rarr; {s.SELF_CORRECTING.hallucinationRate}%</div>
              <div className="stat-label">Hallucination rate on unanswerable questions</div>
            </div>
            <div className="panel">
              <div className="stat">{s.questions}</div>
              <div className="stat-label">gold questions &middot; model <code>{run.provider || 'failover'}</code> &middot; {new Date(run.finishedAt).toLocaleDateString()}</div>
            </div>
          </div>

          <div className="panel bars">
            <div className="panel-title">Metrics</div>
            {METRICS.map(([key, label, lower]) => (
              <div className="bar-row" key={key}>
                <div className="small">{label}{lower && <span className="faint"> (lower is better)</span>}</div>
                <div className="bar-track">
                  <div className="bar" style={{ width: `${Math.max(s.PLAIN[key], 3)}%`, background: '#6c7f9f' }}>{s.PLAIN[key]}%</div>
                  <div className="bar" style={{ width: `${Math.max(s.SELF_CORRECTING[key], 3)}%`, background: 'var(--teal)' }}>{s.SELF_CORRECTING[key]}%</div>
                </div>
              </div>
            ))}
            <div className="small muted">
              <span className="badge grey">grey = plain RAG</span> <span className="badge teal">teal = PolicyGuard</span> &middot; avg latency{' '}
              {s.PLAIN.avgLatencyMs} ms vs {s.SELF_CORRECTING.avgLatencyMs} ms (self-correction costs extra LLM calls)
            </div>
          </div>

          <div className="grid-2" style={{ marginTop: 16 }}>
            <div className="panel">
              <div className="panel-title">Threshold calibration</div>
              <SweepChart rows={s.thresholdSweep} current={status && status.minSimilarity} />
              <div className="small muted">Higher threshold = more refusals of both kinds. We pick the point that maximises correct refusals minus false refusals.</div>
            </div>
            <div className="panel">
              <div className="panel-title">Per question</div>
              <div className="table-wrap" style={{ maxHeight: 330, overflowY: 'auto' }}>
                <table>
                  <thead><tr><th>ID</th><th>Type</th><th>Plain</th><th>PolicyGuard</th></tr></thead>
                  <tbody>
                    {Object.values(byId).map((q) => (
                      <tr key={q.id}>
                        <td className="mono">{q.id}</td>
                        <td className="small">{q.category.toLowerCase()}</td>
                        {['PLAIN', 'SELF_CORRECTING'].map((m) => (
                          <td key={m} className="small" title={q[m] && q[m].answer}>
                            {q[m] && <span className={'badge ' + (q[m].correct ? 'teal' : 'red')}>{q[m].correct ? '✓' : '✗'} {q[m].outcome.toLowerCase()}</span>}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </>
      )}

      <div className="panel" style={{ marginTop: 16 }}>
        <div className="panel-title"><FlaskConical size={15} /> Run the evaluation</div>
        <div className="row">
          <div>
            <span className="label">LLM for both arms</span>
            <select className="select input" value={provider} onChange={(e) => setProvider(e.target.value)}>
              <option value="ollama">ollama (local, no quota)</option>
              <option value="groq">groq</option>
              <option value="gemini">gemini</option>
              <option value="auto">failover chain</option>
            </select>
          </div>
          {status && status.adminProtected && (
            <div><span className="label">Admin key</span><input className="input" type="password" value={adminKey} onChange={(e) => setAdminKey(e.target.value)} /></div>
          )}
          <button className="btn primary" style={{ marginTop: 18 }} onClick={start} disabled={current && current.status === 'RUNNING'}>
            {current && current.status === 'RUNNING' ? <Loader2 size={15} className="spin" /> : <Play size={15} />} Run
          </button>
          {current && current.status === 'RUNNING' && <span className="small muted" style={{ marginTop: 18 }}>Running&hellip; every question twice. This takes several minutes.</span>}
        </div>
      </div>
    </>
  );
}
