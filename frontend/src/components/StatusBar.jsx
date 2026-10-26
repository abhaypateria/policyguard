import { useEffect, useState } from 'react';
import { api } from '../api.js';

/** Live system status. Free hosting sleeps when idle, so a failed call shows "waking up" and retries. */
export default function StatusBar() {
  const [status, setStatus] = useState(null);
  const [down, setDown] = useState(false);

  useEffect(() => {
    let alive = true;
    const load = () =>
      api.status()
        .then((s) => alive && (setStatus(s), setDown(false)))
        .catch(() => alive && setDown(true));
    load();
    const t = setInterval(load, 15000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  if (down) {
    return (
      <div className="statusbar">
        <span><span className="dot warn" />Backend is waking up (free hosting sleeps when idle) &mdash; retrying automatically&hellip;</span>
      </div>
    );
  }
  if (!status) return <div className="statusbar"><span className="faint">Connecting&hellip;</span></div>;

  const d = status.documents;
  return (
    <div className="statusbar">
      <span><span className="dot ok" /><b>Online</b></span>
      <span><b>{d.indexed}</b> documents indexed{d.processing > 0 && <> &middot; <b>{d.processing}</b> processing</>}</span>
      <span><b>{status.chunks}</b> chunks in pgvector</span>
      <span><b>{status.auditRecords}</b> audit records</span>
      <span>
        LLM:&nbsp;
        {status.llmProviders.map((p) => (
          <span key={p.name} style={{ marginRight: 10 }} title={p.model}>
            <span className={'dot ' + (!p.configured ? 'off' : p.coolingDown ? 'warn' : 'ok')} />
            {p.name}
          </span>
        ))}
      </span>
    </div>
  );
}
