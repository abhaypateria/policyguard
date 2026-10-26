import { Fragment, useCallback, useEffect, useState } from 'react';
import { Link2, Loader2, ShieldAlert, ShieldCheck } from 'lucide-react';
import { api } from '../api.js';

const short = (h) => (h ? h.slice(0, 10) + '…' : '');

export default function AuditPage() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [open, setOpen] = useState(null);
  const [verify, setVerify] = useState(null);
  const [checking, setChecking] = useState(false);
  const [head, setHead] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.audit(page, 20).then(setData).catch((e) => setError(e.message));
    api.head().then(setHead).catch(() => {});
  }, [page]);

  useEffect(load, [load]);

  const runVerify = async () => {
    setChecking(true);
    try {
      setVerify(await api.verify());
    } catch (e) {
      setError(e.message);
    } finally {
      setChecking(false);
    }
  };

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Audit log</h1>
          <p className="page-sub">
            Every question, answer, source and confidence is recorded. Records are <b>hash-chained</b>: each one&rsquo;s
            SHA-256 includes the previous record&rsquo;s hash, so editing or deleting any past record is detectable.
          </p>
        </div>
        <button className="btn primary" onClick={runVerify} disabled={checking}>
          {checking ? <Loader2 size={16} className="spin" /> : <ShieldCheck size={16} />} Verify chain integrity
        </button>
      </div>
      {error && <div className="panel err">{error}</div>}

      {verify && (
        <div className={'banner ' + (verify.intact ? 'teal' : 'red')} style={{ marginBottom: 16, padding: 16 }}>
          {verify.intact ? <ShieldCheck size={26} color="var(--teal)" /> : <ShieldAlert size={26} color="var(--red)" />}
          <div>
            <div style={{ fontWeight: 700, fontSize: 17 }}>
              {verify.intact ? `Chain intact — ${verify.checked} records verified` : `TAMPERING DETECTED at record #${verify.brokenAtSeq}`}
            </div>
            <div className="small">{verify.reason} &middot; recomputed in {verify.verifyMs} ms</div>
          </div>
        </div>
      )}

      <div className="grid-2" style={{ marginBottom: 16 }}>
        <div className="panel">
          <div className="panel-title"><Link2 size={15} /> Chain head (anchor)</div>
          {head && (
            <>
              <div className="small muted">Latest record #{head.seq}</div>
              <div className="hash" style={{ wordBreak: 'break-all', color: 'var(--text)' }}>{head.hash}</div>
              <div className="small faint" style={{ marginTop: 6 }}>
                Publishing this value outside the database (e-mail, ticket, print-out) also makes deletion of the
                newest records provable.
              </div>
            </>
          )}
        </div>
        <div className="panel">
          <div className="panel-title">Try it: simulate an insider edit</div>
          <div className="small muted">In the database console, run:</div>
          <pre className="hash" style={{ whiteSpace: 'pre-wrap', margin: '6px 0' }}>UPDATE audit_record SET answer = answer || ' (edited)' WHERE seq = 2;</pre>
          <div className="small muted">then press <b>Verify chain integrity</b>. To undo:</div>
          <pre className="hash" style={{ whiteSpace: 'pre-wrap', margin: '6px 0' }}>UPDATE audit_record SET answer = left(answer, length(answer) - 9) WHERE seq = 2;</pre>
        </div>
      </div>

      <div className="panel">
        {!data ? (
          <div className="skeleton" />
        ) : (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>#</th><th>Time</th><th>User</th><th>Question</th><th>Outcome</th><th>Conf.</th><th>Prev hash &rarr; hash</th></tr>
                </thead>
                <tbody>
                  {data.content.map((r) => (
                    <Fragment key={r.seq}>
                      <tr className="clickable" onClick={() => setOpen(open === r.seq ? null : r.seq)}>
                        <td><b>{r.seq}</b></td>
                        <td className="small">{new Date(r.createdAt).toLocaleString()}</td>
                        <td className="small">{r.userId}</td>
                        <td style={{ maxWidth: 380 }}>{r.question}</td>
                        <td>
                          <span className={'badge ' + (r.outcome === 'REFUSED' ? 'amber' : r.mode === 'UNGROUNDED' ? 'red' : 'teal')}>
                            {r.mode === 'UNGROUNDED' ? 'ungrounded' : r.outcome.toLowerCase()}
                          </span>
                        </td>
                        <td className="small">{r.confidence != null ? Math.round(r.confidence * 100) + '%' : '-'}</td>
                        <td className="hash">{short(r.prevHash)} &rarr; {short(r.hash)}</td>
                      </tr>
                      {open === r.seq && (
                        <tr>
                          <td colSpan={7} style={{ background: 'var(--bg-2)' }}>
                            <div className="small" style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}>{r.answer}</div>
                            <div className="small muted">Mode {r.mode} &middot; as of {r.asOfDate} &middot; {r.attempts} attempt(s) &middot; {r.llmProvider} &middot; {r.latencyMs} ms</div>
                            <div className="small muted">Document versions: <code>{r.documentVersions}</code></div>
                            <div className="small muted">Cited: <code>{r.citationsJson}</code></div>
                            <div className="hash" style={{ marginTop: 6, wordBreak: 'break-all' }}>hash {r.hash}</div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="row" style={{ marginTop: 12 }}>
              <button className="btn small" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
              <span className="small muted">page {data.page + 1} of {Math.max(data.totalPages, 1)} &middot; {data.totalElements} records</span>
              <button className="btn small" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older</button>
            </div>
          </>
        )}
      </div>
    </>
  );
}
