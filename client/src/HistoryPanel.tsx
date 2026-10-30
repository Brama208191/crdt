import { useEffect, useState } from 'react';
import { api, type Version } from './api';

export function HistoryPanel({ docId }: { docId: string }) {
  const [versions, setVersions] = useState<Version[] | null>(null);
  const [selected, setSelected] = useState<{ seq: number; content: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = () =>
    api
      .versions(docId)
      .then(setVersions)
      .catch((e: Error) => setError(e.message));

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId]);

  const preview = async (seq: number) => setSelected(await api.version(docId, seq));

  const restore = async () => {
    if (!selected || !confirm('Restore this version? Everyone editing will see the change.')) return;
    await api.restore(docId, selected.seq);
    setSelected(null);
    void refresh();
  };

  return (
    <aside className="history">
      <div className="history-head">
        <h2>History</h2>
        <button onClick={refresh}>Refresh</button>
      </div>
      <p className="muted small">A snapshot is saved every 50 edits.</p>
      {error && <p className="notice bad">{error}</p>}
      {versions?.length === 0 && <p className="hand">No snapshots yet, keep typing!</p>}
      <ul className="versions">
        {versions?.map((v) => (
          <li key={v.seq}>
            <button className={selected?.seq === v.seq ? 'version current' : 'version'} onClick={() => preview(v.seq)}>
              <span className="version-num">edit #{v.seq}</span>
              <span className="muted small">{new Date(v.createdAt).toLocaleString()}</span>
              <span className="version-preview">{v.preview || '(empty)'}</span>
            </button>
          </li>
        ))}
      </ul>
      {selected && (
        <div className="preview">
          <pre>{selected.content || '(empty)'}</pre>
          <button className="primary" onClick={restore}>
            Restore this version
          </button>
        </div>
      )}
    </aside>
  );
}
