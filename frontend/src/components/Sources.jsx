import { ExternalLink } from 'lucide-react';
import { api } from '../api.js';

/** The labelled source passages (S1, S2...) the answer was built from, each linking to the exact PDF page. */
export default function Sources({ sources, flash, refused }) {
  if (!sources || sources.length === 0) {
    return (
      <div className="panel">
        <div className="panel-title">Sources</div>
        <div className="muted small">No passages matched &mdash; nothing to cite.</div>
      </div>
    );
  }
  return (
    <div className="panel">
      <div className="panel-title">{refused ? 'Closest passages (not sufficient)' : 'Sources'}</div>
      {sources.map((s) => (
        <div key={s.label} id={'src-' + s.label} className={'source' + (s.cited ? ' cited' : '') + (flash === s.label ? ' flash' : '')}>
          <div className="source-head">
            <span>
              <span className="source-label">{s.label}</span>{' '}
              <span className={'badge ' + (s.sourceType === 'REGULATOR' ? 'blue' : 'violet')}>
                {s.sourceType === 'REGULATOR' ? 'Regulator' : 'Internal'}
              </span>{' '}
              {s.cited && <span className="badge teal">cited</span>}
            </span>
            <span className="small faint" title="cosine similarity to the query">sim {s.similarity.toFixed(2)}</span>
          </div>
          <div className="source-title">{s.title}</div>
          <div className="source-meta">
            {s.issuer} &middot; version <b>{s.versionLabel}</b> &middot; page <b>{s.page}</b>
            {s.effectiveTo && <span style={{ color: 'var(--amber)' }}> &middot; superseded {s.effectiveTo}</span>}
          </div>
          <div className="source-snippet">{s.snippet}</div>
          <a className="small" href={api.fileUrl(s.documentId, s.page)} target="_blank" rel="noreferrer" style={{ display: 'inline-flex', gap: 5, alignItems: 'center', marginTop: 6 }}>
            Open PDF at page {s.page} <ExternalLink size={12} />
          </a>
        </div>
      ))}
    </div>
  );
}
