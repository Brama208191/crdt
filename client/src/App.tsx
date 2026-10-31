import { useEffect, useMemo, useState } from 'react';
import { AdminPage } from './AdminPage';
import { EditorPage } from './EditorPage';
import { Home } from './Home';

// Chalk colors that read well on the dark board.
const COLORS = ['#f2d15c', '#f29fb0', '#8fd3c1', '#9fc5f8', '#c9a7f2', '#f5b97a'];

function useHash() {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const onChange = () => setHash(location.hash);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return hash;
}

function initialName(): string {
  try {
    const saved = localStorage.getItem('name');
    if (saved) return saved;
  } catch {
    // storage unavailable
  }
  return `Guest ${Math.floor(Math.random() * 900 + 100)}`;
}

export default function App() {
  const hash = useHash();
  const [name, setName] = useState(initialName);
  const [draft, setDraft] = useState(name);
  const color = useMemo(() => COLORS[Math.floor(Math.random() * COLORS.length)], []);
  const me = useMemo(() => ({ name, color }), [name, color]);
  const docId = hash.match(/^#\/d\/([0-9a-f-]{36})$/)?.[1];

  const rename = (value: string) => {
    setName(value);
    try {
      localStorage.setItem('name', value);
    } catch {
      // storage unavailable
    }
  };

  return (
    <>
      <div className="identity">
        <span className="dot" style={{ background: color }} />
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => draft.trim() && rename(draft.trim())}
          onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
          aria-label="Your name"
          maxLength={30}
        />
      </div>
      {docId ? <EditorPage key={docId} docId={docId} me={me} /> : hash === '#/admin' ? <AdminPage /> : <Home />}
    </>
  );
}
