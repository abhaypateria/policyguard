import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { FileStack, FlaskConical, MessageSquareText, Network, ShieldCheck } from 'lucide-react';
import StatusBar from './components/StatusBar.jsx';
import AskPage from './pages/AskPage.jsx';
import DocumentsPage from './pages/DocumentsPage.jsx';
import AuditPage from './pages/AuditPage.jsx';
import EvaluationPage from './pages/EvaluationPage.jsx';
import ArchitecturePage from './pages/ArchitecturePage.jsx';

const nav = ({ isActive }) => 'nav-link' + (isActive ? ' active' : '');

export default function App() {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <ShieldCheck size={21} color="#fff" />
          </div>
          <div>
            <div className="brand-name">PolicyGuard</div>
            <div className="brand-sub">Compliance answers you can audit</div>
          </div>
        </div>
        <NavLink to="/" end className={nav}><MessageSquareText size={18} /> Ask</NavLink>
        <NavLink to="/documents" className={nav}><FileStack size={18} /> Documents</NavLink>
        <NavLink to="/audit" className={nav}><ShieldCheck size={18} /> Audit log</NavLink>
        <NavLink to="/evaluation" className={nav}><FlaskConical size={18} /> Evaluation</NavLink>
        <NavLink to="/architecture" className={nav}><Network size={18} /> How it works</NavLink>
        <div className="sidebar-foot">
          TARP project &middot; Madhur Pateria
          <br />
          Demo corpus: public RBI KYC directions + a <i>fictional</i> bank SOP.
        </div>
      </aside>
      <main className="main">
        <StatusBar />
        <Routes>
          <Route path="/" element={<AskPage />} />
          <Route path="/ask" element={<Navigate to="/" replace />} />
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/evaluation" element={<EvaluationPage />} />
          <Route path="/architecture" element={<ArchitecturePage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
