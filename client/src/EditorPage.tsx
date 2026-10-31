import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createEditor } from 'slate';
import { withHistory } from 'slate-history';
import { Editable, Slate, withReact } from 'slate-react';
import { api, type Participant } from './api';
import type { Id } from './crdt/rga';
import { HistoryPanel } from './HistoryPanel';
import { SiteBar } from './SiteBar';
import { editorText, pointToOffset, setEditorText, toSlate } from './slateText';
import { DocSession, type Status } from './sync';

// Must match DocumentService.MAX_CHARS on the server.
const MAX_CHARS = 100_000;

interface Props {
  docId: string;
  me: { name: string; color: string };
}

export function EditorPage({ docId, me }: Props) {
  const editor = useMemo(() => withHistory(withReact(createEditor())), []);
  const session = useRef<DocSession | null>(null);
  // Cursor anchored to CRDT ids, so remote edits elsewhere don't shift it.
  const cursor = useRef<{ anchor: Id | null; focus: Id | null } | null>(null);
  const [status, setStatus] = useState<Status>('connecting');
  const [people, setPeople] = useState<Participant[]>([]);
  const [title, setTitle] = useState('');
  const [showHistory, setShowHistory] = useState(false);
  const [copied, setCopied] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [canManage, setCanManage] = useState(false);
  const [deleted, setDeleted] = useState(false);

  const rememberCursor = useCallback(() => {
    const rga = session.current?.rga;
    if (!rga || !editor.selection) {
      cursor.current = null;
      return;
    }
    cursor.current = {
      anchor: rga.anchorAt(pointToOffset(editor, editor.selection.anchor)),
      focus: rga.anchorAt(pointToOffset(editor, editor.selection.focus)),
    };
  }, [editor]);

  useEffect(() => {
    const s = new DocSession(docId, me, {
      onRemoteChange: () => {
        const text = s.rga.text();
        if (text === editorText(editor)) return;
        const saved = cursor.current;
        setEditorText(
          editor,
          text,
          saved && { anchor: s.rga.positionOf(saved.anchor), focus: s.rga.positionOf(saved.focus) },
        );
        rememberCursor();
      },
      onPresence: setPeople,
      onStatus: setStatus,
      onTitle: setTitle,
      onDeleted: () => {
        setDeleted(true);
        s.dispose();
      },
    });
    session.current = s;
    return () => s.dispose();
  }, [docId, me, editor, rememberCursor]);

  useEffect(() => {
    api
      .access(docId)
      .then((a) => setCanManage(a.canManage))
      .catch(() => setCanManage(false));
  }, [docId]);

  const handleChange = () => {
    const s = session.current;
    if (!s) return;
    if (editor.operations.some((op) => op.type !== 'set_selection')) {
      const text = editorText(editor);
      if (text.length > MAX_CHARS) {
        // Revert to the last synced text rather than send an edit the server would reject.
        const offset = s.rga.text().length;
        setEditorText(editor, s.rga.text(), { anchor: offset, focus: offset });
        setNotice(`Documents are limited to ${MAX_CHARS.toLocaleString()} characters.`);
        return;
      }
      setNotice(null);
      s.sendLocal(s.rga.replaceText(text));
    }
    rememberCursor();
  };

  const remove = async () => {
    if (!confirm(`Delete "${title}" for everyone? This can't be undone.`)) return;
    try {
      await api.deleteDoc(docId);
      location.hash = '#/';
    } catch (e) {
      setNotice(String(e).includes(': 403') ? "This browser isn't allowed to delete this document." : 'Delete failed. Please try again.');
    }
  };

  const copyLink = async () => {
    await navigator.clipboard.writeText(location.href);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  if (deleted) {
    return (
      <>
        <SiteBar />
        <div className="page">
          <section className="hero">
            <h1>This document was deleted or never existed.</h1>
            <p>
              <a href="#/">Back to all documents →</a>
            </p>
          </section>
        </div>
      </>
    );
  }

  return (
    <div className="workspace">
      <SiteBar
        right={
          <>
            <button onClick={copyLink}>{copied ? 'Copied!' : 'Share link'}</button>
            <button className={showHistory ? 'light' : ''} onClick={() => setShowHistory((v) => !v)}>
              History
            </button>
            {canManage && (
              <button className="danger" onClick={remove}>
                Delete
              </button>
            )}
          </>
        }
      />
      <div className={showHistory ? 'workspace-body with-history' : 'workspace-body'}>
        <main className="doc-pane">
          <div className="doc-head">
            <h1>{title}</h1>
            <div className="meta">
              <span className={`status ${status}`}>{status}</span>
              {people.map((p) => (
                <span key={p.sessionId} className="person" style={{ color: p.color, borderColor: p.color }}>
                  {p.name}
                </span>
              ))}
            </div>
          </div>
          {notice && <p className="notice bad">{notice}</p>}
          <div className="editor-card">
            <div className="editor-head">
              <span>
                editing as <span style={{ color: me.color }}>{me.name}</span>
              </span>
              <span className="hand">
                {people.length > 1 ? `${people.length} here` : 'just you'}
              </span>
            </div>
            <Slate editor={editor} initialValue={toSlate('')} onChange={handleChange}>
              <Editable
                className="editable"
                placeholder="Start typing… then open this link in another tab."
                readOnly={status === 'connecting'}
                spellCheck
              />
            </Slate>
          </div>
        </main>
        {showHistory && <HistoryPanel docId={docId} canManage={canManage} />}
      </div>
    </div>
  );
}
