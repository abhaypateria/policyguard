import { AlertTriangle, Ban, CalendarClock, CheckCheck, Filter, Link2, Lock, RefreshCw, Scale, Search, Sparkles, Zap } from 'lucide-react';

const STEPS = {
  SCOPE: { icon: CalendarClock, color: 'var(--blue)', label: 'Scope by date' },
  RETRIEVE: { icon: Search, color: 'var(--blue)', label: 'Retrieve' },
  GATE: { icon: Filter, color: 'var(--amber)', label: 'Threshold gate' },
  GRADE: { icon: Scale, color: 'var(--violet)', label: 'Grade evidence' },
  REWRITE: { icon: RefreshCw, color: 'var(--amber)', label: 'Rewrite & retry' },
  GENERATE: { icon: Sparkles, color: 'var(--teal)', label: 'Generate' },
  ATTRIBUTE: { icon: Link2, color: 'var(--teal)', label: 'Attribute citations' },
  VERIFY: { icon: CheckCheck, color: 'var(--teal)', label: 'Verify claims' },
  CONFLICT: { icon: AlertTriangle, color: 'var(--red)', label: 'Conflict' },
  REFUSE: { icon: Ban, color: 'var(--amber)', label: 'Refuse' },
  AUDIT: { icon: Lock, color: 'var(--violet)', label: 'Seal in audit log' },
  CACHE: { icon: Zap, color: 'var(--muted)', label: 'Cache' },
};

/** The self-correction loop, step by step, exactly as it ran for this question. */
export default function Trace({ steps }) {
  if (!steps || steps.length === 0) return null;
  return (
    <div className="panel">
      <div className="panel-title">How this answer was produced</div>
      <ul className="trace">
        {steps.map((s, i) => {
          const meta = STEPS[s.step] || { icon: Sparkles, color: 'var(--muted)', label: s.step };
          const Icon = meta.icon;
          return (
            <li key={i}>
              <span className="node" style={{ borderColor: meta.color }}><Icon size={12} color={meta.color} /></span>
              <div>
                <span className="step-name" style={{ color: meta.color }}>{meta.label.toUpperCase()}</span>
                {s.ms > 0 && <span className="ms">{s.ms} ms</span>}
              </div>
              <div className="step-detail">{s.detail}</div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
