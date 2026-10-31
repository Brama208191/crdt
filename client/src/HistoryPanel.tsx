import { useEffect, useState } from 'react';
import { api, type Version } from './api';

export function HistoryPanel({ docId, canManage }: { docId: string; canManage: boolean }) {
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

  const clear = async () => {
    if (
      !confirm(
        'Clear all saved versions? The current text stays, but older versions and deleted text are erased for good.',
      )
    )
      return;
    try {
      await api.clearHistory(docId);
      setSelected(null);
      setError(null);
      void refresh();
    } catch (e) {
      setError(
        String(e).includes(': 409')
          ? 'Edits are still arriving. Try again in a moment.'
          : String(e).includes(': 403')
            ? "This browser isn't allowed to clear this history."
            : 'Clearing history failed. Please try again.',
      );
    }
  };

  return (
    <aside className="history">
      <div className="history-head">
        <h2>History</h2>
        <div className="history-actions">
          <button onClick={refresh}>Refresh</button>
          {canManage && (
            <button className="danger" onClick={clear}>
              Clear
            </button>
          )}
        </div>
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
