import { describe, expect, it } from 'vitest';
import { Rga, type Op } from './rga';

// Small deterministic PRNG so failures are reproducible.
function mulberry32(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

describe('Rga', () => {
  it('converges on concurrent inserts at the same position', () => {
    const a = new Rga('a');
    const b = new Rga('b');
    a.insert(0, 'ac').forEach((op) => b.apply(op));
    const fromA = a.insert(1, 'X');
    const fromB = b.insert(1, 'Y');
    fromB.forEach((op) => a.apply(op));
    fromA.forEach((op) => b.apply(op));
    expect(a.text()).toBe(b.text());
  });

  it('keeps the cursor anchored across a remote insert before it', () => {
    const a = new Rga('a');
    const b = new Rga('b');
    a.insert(0, 'hello').forEach((op) => b.apply(op));
    const anchor = a.anchorAt(5);
    b.insert(0, '>> ').forEach((op) => a.apply(op));
    expect(a.positionOf(anchor)).toBe(8);
  });

  it.each([1, 2, 3, 42])('converges under reordering and duplication (seed %i)', (seed) => {
    const rand = mulberry32(seed);
    const pick = (n: number) => Math.floor(rand() * n);
    const replicas = [0, 1, 2, 3].map((i) => new Rga(`site-${i}`));
    const inboxes: Op[][] = replicas.map(() => []);

    for (let round = 0; round < 1500; round++) {
      const r = pick(replicas.length);
      const replica = replicas[r];
      if (pick(3) === 0) {
        const inbox = inboxes[r];
        for (let i = pick(inbox.length + 1); i > 0; i--) replica.apply(inbox.splice(pick(inbox.length), 1)[0]);
        continue;
      }
      const len = replica.text().length;
      const ops =
        len > 0 && pick(3) === 0
          ? replica.delete(pick(len), 1)
          : replica.insert(pick(len + 1), 'abc \n'[pick(5)].repeat(1 + pick(3)));
      inboxes.forEach((inbox, other) => {
        if (other === r) return;
        for (const op of ops) {
          inbox.push(op);
          if (pick(10) === 0) inbox.push(op);
        }
      });
    }
    replicas.forEach((replica, r) => {
      const inbox = inboxes[r];
      while (inbox.length) replica.apply(inbox.splice(pick(inbox.length), 1)[0]);
    });

    const expected = replicas[0].text();
    expect(expected.length).toBeGreaterThan(0);
    for (const replica of replicas) {
      expect(replica.text()).toBe(expected);
      expect(replica.pendingCount).toBe(0);
    }
  });
});
