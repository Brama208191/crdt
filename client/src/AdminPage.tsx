import { useEffect, useState } from 'react';
import { api } from './api';
import { SiteBar } from './SiteBar';

/** Sign-in for the site owner. The password is sent once; the session lives in an HttpOnly cookie. */
export function AdminPage() {
  const [status, setStatus] = useState<{ enabled: boolean; signedIn: boolean } | null>(null);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const refresh = () =>
    api
      .adminStatus()
      .then(setStatus)
      .catch(() => setError('Could not reach the server. Try again in a few seconds.'));

  useEffect(() => {
    void refresh();
  }, []);

  const signIn = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.adminSignIn(password);
      setPassword('');
      void refresh();
    } catch (err) {
      setPassword('');
      setError(
        String(err).includes(': 429')
          ? 'Too many attempts. Wait 15 minutes and try again.'
          : 'Wrong password.',
      );
    }
  };

  const signOut = async () => {
    await api.adminSignOut();
    void refresh();
  };

  return (
    <>
      <SiteBar />
      <div className="page">
        <section className="hero">
          <h1>Admin</h1>
          {status && !status.enabled && <p>Admin sign-in isn't set up on this server.</p>}
          {status?.enabled && status.signedIn && (
            <>
              <p>You're signed in as admin. You can delete any document and clear its history.</p>
              <p>
                <a href="#/">Go to documents →</a>
              </p>
              <button onClick={signOut}>Sign out</button>
            </>
          )}
          {status?.enabled && !status.signedIn && (
            <form onSubmit={signIn} className="create">
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Admin password"
                aria-label="Admin password"
                required
              />
              <button className="primary">Sign in</button>
            </form>
          )}
          {error && <p className="notice bad">{error}</p>}
        </section>
      </div>
    </>
  );
}
