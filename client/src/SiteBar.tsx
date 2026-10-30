import type { ReactNode } from 'react';

export function SiteBar({ right }: { right?: ReactNode }) {
  return (
    <header className="site-bar">
      <a href="#/" className="wordmark">
        crdt editor
      </a>
      <span className="hand tagline">write together, merge cleanly</span>
      <span className="spacer" />
      {right}
    </header>
  );
}
