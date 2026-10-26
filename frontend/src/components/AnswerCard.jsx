import { useState } from 'react';
import { AlertTriangle, Ban, CheckCircle2, History, Lock, ShieldAlert, Zap } from 'lucide-react';

/** Turn "[S1]" into clickable citation chips and "**x**" into bold; group bullet lines into lists. */
function renderAnswer(text, onCite) {
  const inline = (line, key) =>
    line.split(/(\[S\d+\]|\*\*[^*]+\*\*)/g).filter(Boolean).map((part, i) => {
      const cite = part.match(/^\[(S\d+)\]$/);
      if (cite) {
        return (
          <span key={key + '-' + i} className="cite" onClick={() => onCite && onCite(cite[1])} title="Show this source">
            {cite[1]}
          </span>
        );
      }
      if (part.startsWith('**') && part.endsWith('**')) return <strong key={key + '-' + i}>{part.slice(2, -2)}</strong>;
      return <span key={key + '-' + i}>{part}</span>;
    });

  const blocks = [];
  let list = [];
  const flush = () => {
    if (list.length) blocks.push(<ul key={'ul' + blocks.length}>{list}</ul>);
    list = [];
  };
  text.split(/\r?\n/).forEach((raw, idx) => {
    const line = raw.trim();
    if (!line) return flush();
    const bullet = line.match(/^([-*•]|\d+[.)])\s+(.*)$/);
    if (bullet) list.push(<li key={'li' + idx}>{inline(bullet[2], 'li' + idx)}</li>);
    else {
      flush();
      blocks.push(<p key={'p' + idx}>{inline(line, 'p' + idx)}</p>);
    }
  });
  flush();
  return blocks;
}

export function ConfidenceMeter({ value }) {
  const pct = Math.round(value * 100);
  const color = pct >= 75 ? 'var(--teal)' : pct >= 50 ? 'var(--amber)' : 'var(--red)';
  return (
    <span className="meter" title="Confidence = retrieval strength + groundedness + grader verdict - retries">
      <span className="meter-track"><span className="meter-fill" style={{ display: 'block', width: pct + '%', background: color }} /></span>
      <span className="small" style={{ color, fontWeight: 700 }}>{pct}% confidence</span>
    </span>
  );
}

export default function AnswerCard({ r, onCite, title }) {
  const [showClaims, setShowClaims] = useState(false);
  const refused = r.outcome === 'REFUSED';
  const ungrounded = r.mode === 'UNGROUNDED';
  const g = r.grounding;

  return (
    <div className="panel">
      {title && <div className="panel-title">{title}</div>}
      <div className="answer-head">
        {refused ? (
          <span className="badge amber"><Ban size={13} /> Refused &mdash; not in the policy documents</span>
        ) : ungrounded ? (
          <span className="badge red"><ShieldAlert size={13} /> Unverifiable &mdash; no sources</span>
        ) : (
          <span className="badge teal"><CheckCircle2 size={13} /> Grounded answer</span>
        )}
        {r.confidence != null && <ConfidenceMeter value={r.confidence} />}
        <span className="badge grey">rules as of {r.asOfDate}</span>
        {!ungrounded && (
          <span className="badge blue">
            {r.mode === 'PLAIN' ? 'Plain RAG (baseline)' : `Self-correcting · ${r.attempts} attempt${r.attempts === 1 ? '' : 's'}`}
          </span>
        )}
        {r.cached && <span className="badge violet"><Zap size={12} /> cached</span>}
        {r.auditSeq && (
          <span className="badge violet" title={'hash ' + r.auditHash}><Lock size={12} /> audit #{r.auditSeq}</span>
        )}
      </div>

      <div className={'answer-body ' + (refused ? 'refusal' : ungrounded ? 'ungrounded' : '')}>
        {renderAnswer(r.answer || '', onCite)}
      </div>

      {r.conflicts && r.conflicts.length > 0 && (
        <div className="banner red">
          <AlertTriangle size={18} color="var(--red)" style={{ flexShrink: 0, marginTop: 2 }} />
          <div>
            <b>Conflict between sources detected.</b>
            {r.conflicts.map((c, i) => (
              <div key={i} className="small">
                {c.sources.join(' vs ')}: {c.description}
              </div>
            ))}
            <div className="small muted">A regulator (RBI) rule prevails over internal policy &mdash; flag the internal SOP to Compliance.</div>
          </div>
        </div>
      )}

      {r.notices && r.notices.map((n, i) => (
        <div key={i} className={'banner ' + (ungrounded ? 'red' : 'amber')}>
          {ungrounded ? <ShieldAlert size={18} color="var(--red)" style={{ flexShrink: 0, marginTop: 2 }} />
            : <History size={18} color="var(--amber)" style={{ flexShrink: 0, marginTop: 2 }} />}
          <div className="small">{n}</div>
        </div>
      ))}

      {g && g.claims > 0 && (
        <div style={{ marginTop: 12 }}>
          <button className="btn small" onClick={() => setShowClaims((v) => !v)}>
            <CheckCircle2 size={14} color="var(--teal)" /> {g.supported}/{g.claims} claims verified against their cited source
          </button>
          {showClaims && (
            <div style={{ marginTop: 8 }}>
              {g.sentences.map((s, i) => (
                <div key={i} className="small" style={{ margin: '6px 0' }}>
                  <span className={'badge ' + (s.status === 'SUPPORTED' ? 'teal' : s.status === 'WEAK' ? 'amber' : 'red')}>
                    {s.status}
                  </span>{' '}
                  {s.text} <span className="faint">({Math.round(s.support * 100)}% term overlap)</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="small faint" style={{ marginTop: 12 }}>
        {r.provider ? `Model: ${r.provider} · ` : ''}{r.latencyMs} ms
      </div>
    </div>
  );
}
