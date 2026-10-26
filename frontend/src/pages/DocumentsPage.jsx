import { useCallback, useEffect, useState } from 'react';
import { ExternalLink, Loader2, Trash2, Upload } from 'lucide-react';
import { api, storage } from '../api.js';

const today = () => new Date().toISOString().slice(0, 10);
const days = (d) => new Date(d + 'T00:00:00Z').getTime() / 86400000;

/** Visual version timeline: which version of a policy was in force when. */
function Timeline({ versions }) {
  const start = Math.min(...versions.map((v) => days(v.effectiveFrom)));
  const end = days(today());
  const span = Math.max(end - start, 1);
  return (
    <div className="timeline">
      {versions.map((v) => {
        const from = days(v.effectiveFrom);
        const to = v.effectiveTo ? days(v.effectiveTo) : end;
        return (
          <div
            key={v.id}
            className="seg-bar"
            title={`${v.versionLabel}: ${v.effectiveFrom} → ${v.effectiveTo || 'today'}`}
            style={{
              left: ((from - start) / span) * 100 + '%',
              width: Math.max(((to - from) / span) * 100, 4) + '%',
              background: v.current ? 'var(--teal-bg)' : 'rgba(148,166,196,.12)',
              border: '1px solid ' + (v.current ? 'rgba(25,179,163,.5)' : 'var(--line)'),
              color: v.current ? 'var(--teal)' : 'var(--muted)',
            }}
          >
            {v.versionLabel} {v.current ? '(current)' : ''}
          </div>
        );
      })}
    </div>
  );
}

export default function DocumentsPage() {
  const [docs, setDocs] = useState([]);
  const [error, setError] = useState('');
  const [adminProtected, setAdminProtected] = useState(false);
  const [adminKey, setAdminKey] = useState(storage.get('pg.adminKey'));
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ title: '', issuer: '', sourceType: 'INTERNAL', documentFamily: '', versionLabel: '', effectiveFrom: today() });
  const [file, setFile] = useState(null);

  const load = useCallback(() => api.documents().then(setDocs).catch((e) => setError(e.message)), []);

  useEffect(() => {
    load();
    api.status().then((s) => setAdminProtected(s.adminProtected)).catch(() => {});
  }, [load]);

  // Poll while anything is still being indexed
  useEffect(() => {
    if (!docs.some((d) => d.status === 'PROCESSING')) return;
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [docs, load]);

  useEffect(() => storage.set('pg.adminKey', adminKey), [adminKey]);

  const upload = async (e) => {
    e.preventDefault();
    if (!file) return setError('Choose a PDF first');
    setBusy(true);
    setError('');
    const fd = new FormData();
    fd.append('file', file);
    Object.entries(form).forEach(([k, v]) => fd.append(k, v));
    try {
      await api.upload(fd, adminKey, storage.get('pg.user', 'admin'));
      setFile(null);
      e.target.reset();
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (d) => {
    if (!window.confirm(`Delete "${d.title}" (${d.versionLabel}) and all its chunks?`)) return;
    try {
      await api.deleteDocument(d.id, adminKey);
      await load();
    } catch (err) {
      setError(err.message);
    }
  };

  const families = docs.reduce((acc, d) => ((acc[d.documentFamily] = acc[d.documentFamily] || []).push(d), acc), {});
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  return (
    <>
      <div className="page-head">
        <div>
          <h1 className="page-title">Policy documents</h1>
          <p className="page-sub">
            Each upload is one <b>version</b> of a policy. Versions in the same family form a timeline &mdash; a question
            &ldquo;as of&rdquo; a date only searches the versions in force on that date.
          </p>
        </div>
      </div>
      {error && <div className="panel err" style={{ marginBottom: 16 }}>{error}</div>}

      {Object.entries(families).map(([family, versions]) => (
        <div className="panel" key={family}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div>
              <div style={{ fontWeight: 700 }}>{versions[versions.length - 1].title}</div>
              <div className="small muted">
                {versions[0].issuer} &middot; family <code>{family}</code> &middot;{' '}
                <span className={'badge ' + (versions[0].sourceType === 'REGULATOR' ? 'blue' : 'violet')}>
                  {versions[0].sourceType === 'REGULATOR' ? 'Regulator' : 'Internal policy'}
                </span>
              </div>
            </div>
          </div>
          <Timeline versions={versions} />
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Version</th><th>In force</th><th>Status</th><th>Pages / chunks</th><th>SHA-256</th><th /></tr>
              </thead>
              <tbody>
                {versions.map((d) => (
                  <tr key={d.id}>
                    <td><b>{d.versionLabel}</b>{d.current && <span className="badge teal" style={{ marginLeft: 8 }}>current</span>}</td>
                    <td className="small">{d.effectiveFrom} &rarr; {d.effectiveTo ? <span style={{ color: 'var(--amber)' }}>{d.effectiveTo} (superseded)</span> : 'now'}</td>
                    <td>
                      {d.status === 'INDEXED' && <span className="badge teal">indexed</span>}
                      {d.status === 'PROCESSING' && <span className="badge amber"><Loader2 size={12} className="spin" /> indexing</span>}
                      {d.status === 'FAILED' && <span className="badge red" title={d.errorMessage}>failed</span>}
                    </td>
                    <td className="small">{d.pageCount ?? '-'} / {d.chunkCount ?? '-'}</td>
                    <td className="hash" title={d.sha256}>{d.sha256.slice(0, 12)}&hellip;</td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <a className="btn small" href={api.fileUrl(d.id)} target="_blank" rel="noreferrer"><ExternalLink size={13} /> Open</a>{' '}
                      <button className="btn small danger" onClick={() => remove(d)}><Trash2 size={13} /></button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}

      <form className="panel" onSubmit={upload}>
        <div className="panel-title"><Upload size={15} /> Upload a policy or a new version</div>
        <div className="grid-3">
          <div><span className="label">PDF file</span><input className="input" type="file" accept=".pdf,.txt,.md" onChange={(e) => setFile(e.target.files[0])} /></div>
          <div><span className="label">Title</span><input className="input" style={{ width: '100%' }} required value={form.title} onChange={set('title')} placeholder="Internal SOP - KYC" /></div>
          <div><span className="label">Issuer</span><input className="input" style={{ width: '100%' }} required value={form.issuer} onChange={set('issuer')} placeholder="Demo Bank Ltd." /></div>
          <div><span className="label">Document family (groups versions)</span><input className="input" style={{ width: '100%' }} required value={form.documentFamily} onChange={set('documentFamily')} placeholder="DEMOBANK-KYC-SOP" /></div>
          <div><span className="label">Version label</span><input className="input" style={{ width: '100%' }} required value={form.versionLabel} onChange={set('versionLabel')} placeholder="2025" /></div>
          <div><span className="label">In force from</span><input className="input" style={{ width: '100%' }} type="date" required value={form.effectiveFrom} onChange={set('effectiveFrom')} /></div>
          <div>
            <span className="label">Source type</span>
            <select className="select input" style={{ width: '100%' }} value={form.sourceType} onChange={set('sourceType')}>
              <option value="INTERNAL">Internal policy</option>
              <option value="REGULATOR">Regulator</option>
            </select>
          </div>
          {adminProtected && (
            <div><span className="label">Admin key (required on this deployment)</span><input className="input" style={{ width: '100%' }} type="password" value={adminKey} onChange={(e) => setAdminKey(e.target.value)} /></div>
          )}
        </div>
        <div className="row" style={{ marginTop: 14 }}>
          <button className="btn primary" disabled={busy}>{busy ? <Loader2 size={15} className="spin" /> : <Upload size={15} />} Upload &amp; index</button>
          <span className="small muted">Parsed page-by-page, chunked, embedded locally, stored in pgvector &mdash; in the background.</span>
        </div>
      </form>
    </>
  );
}
