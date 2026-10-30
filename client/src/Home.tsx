import { useEffect, useState } from 'react';
import { api, type DocumentSummary } from './api';
import { SiteBar } from './SiteBar';

export function Home() {
  const [docs, setDocs] = useState<DocumentSummary[] | null>(null);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .list()
      .then(setDocs)
      .catch(() => setError('Server is waking up (free hosting sleeps when idle). Refresh in a few seconds.'));
  }, []);

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const { id } = await api.create(title);
      location.hash = `#/d/${id}`;
    } catch (err) {
      setError(
        String(err).includes('429') ? 'You have created a lot of documents; try again in an hour.' : String(err),
      );
    }
  };

  return (
    <>
      <SiteBar />
      <div className="page">
        <section className="hero">
          <h1>Edit the same doc, at the same time.</h1>
          <p>
            Concurrent edits merge with a custom RGA CRDT: no locks, no last-write-wins. Open any document in two
            tabs to try it.
          </p>
          <form onSubmit={create} className="create">
            <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="New document title" />
            <button className="primary">Create</button>
          </form>
        </section>

        {error && <p className="notice bad">{error}</p>}

        {docs && docs.length > 0 && (
          <section className="chapter">
            <h2>
              <span className="chapter-num">recent</span> Documents
            </h2>
            <ul className="syllabus">
              {docs.map((d, i) => (
                <li key={d.id} className="lesson">
                  <a href={`#/d/${d.id}`} className="lesson-row">
                    <span className="lesson-num">{String(i + 1).padStart(2, '0')}</span>
                    <span className="lesson-body">
                      <span className="lesson-title">{d.title}</span>
                      <span className="lesson-meta">edited {new Date(d.updatedAt).toLocaleString()}</span>
                    </span>
                    <span className="lesson-status">open →</span>
                  </a>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </>
  );
}
