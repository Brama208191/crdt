// TypeScript twin of server/src/main/java/dev/crdt/editor/crdt/Rga.java.
// Both sides must order ids identically or replicas would diverge.

export interface Id {
  counter: number;
  site: string;
}

export type Op =
  | { type: 'insert'; id: Id; after: Id | null; value: string }
  | { type: 'delete'; target: Id };

export interface NodeState {
  id: Id;
  value: string;
  deleted: boolean;
}

interface Node extends NodeState {
  next: Node | null;
}

export function compareIds(a: Id, b: Id): number {
  if (a.counter !== b.counter) return a.counter < b.counter ? -1 : 1;
  if (a.site === b.site) return 0;
  return a.site < b.site ? -1 : 1;
}

const key = (id: Id) => `${id.counter}@${id.site}`;

export class Rga {
  private readonly head: Node = { id: { counter: 0, site: '' }, value: '', deleted: true, next: null };
  private readonly index = new Map<string, Node>();
  private pending: Op[] = [];
  private clock = 0;

  readonly site: string;

  constructor(site: string) {
    this.site = site;
  }

  static fromNodes(site: string, nodes: NodeState[]): Rga {
    const rga = new Rga(site);
    let tail = rga.head;
    for (const n of nodes) {
      const node: Node = { ...n, next: null };
      tail.next = node;
      tail = node;
      rga.index.set(key(n.id), node);
      rga.clock = Math.max(rga.clock, n.id.counter);
    }
    return rga;
  }

  // ---- remote ops ----

  /** Returns true if the op was new to this replica (applied or buffered). */
  apply(op: Op): boolean {
    if (this.isDuplicate(op)) return false;
    if (!this.isReady(op)) {
      this.pending.push(op);
      return true;
    }
    this.integrate(op);
    this.drainPending();
    return true;
  }

  get pendingCount(): number {
    return this.pending.length;
  }

  private isDuplicate(op: Op): boolean {
    if (op.type === 'insert') {
      return this.index.has(key(op.id)) || this.pending.some((p) => p.type === 'insert' && key(p.id) === key(op.id));
    }
    const target = this.index.get(key(op.target));
    return (
      (target !== undefined && target.deleted) ||
      this.pending.some((p) => p.type === 'delete' && key(p.target) === key(op.target))
    );
  }

  private isReady(op: Op): boolean {
    return op.type === 'insert'
      ? op.after === null || this.index.has(key(op.after))
      : this.index.has(key(op.target));
  }

  private drainPending() {
    let progressed = true;
    while (progressed) {
      progressed = false;
      const still: Op[] = [];
      for (const op of this.pending) {
        if (this.isReady(op)) {
          this.integrate(op);
          progressed = true;
        } else {
          still.push(op);
        }
      }
      this.pending = still;
    }
  }

  private integrate(op: Op) {
    if (op.type === 'insert') this.integrateInsert(op.id, op.after, op.value);
    else this.index.get(key(op.target))!.deleted = true;
  }

  private integrateInsert(id: Id, after: Id | null, value: string) {
    let prev = after === null ? this.head : this.index.get(key(after))!;
    // Concurrent inserts at the same spot: larger id goes first.
    while (prev.next !== null && compareIds(prev.next.id, id) > 0) prev = prev.next;
    const node: Node = { id, value, deleted: false, next: prev.next };
    prev.next = node;
    this.index.set(key(id), node);
    this.clock = Math.max(this.clock, id.counter);
  }

  // ---- local edits ----

  insert(pos: number, value: string): Op[] {
    const ops: Op[] = [];
    let after = pos === 0 ? null : this.visibleNode(pos - 1).id;
    for (const ch of value.split('')) {
      const id = { counter: this.clock + 1, site: this.site };
      this.integrateInsert(id, after, ch);
      ops.push({ type: 'insert', id, after, value: ch });
      after = id;
    }
    return ops;
  }

  delete(pos: number, length: number): Op[] {
    const ops: Op[] = [];
    let node: Node | null = length > 0 ? this.visibleNode(pos) : null;
    for (let i = 0; i < length; i++) {
      while (node!.deleted) node = node!.next;
      node!.deleted = true;
      ops.push({ type: 'delete', target: node!.id });
      node = node!.next;
    }
    return ops;
  }

  /** Turns the current text into `target` with a minimal prefix/suffix diff. */
  replaceText(target: string): Op[] {
    const current = this.text();
    const max = Math.min(current.length, target.length);
    let prefix = 0;
    while (prefix < max && current[prefix] === target[prefix]) prefix++;
    let suffix = 0;
    while (suffix < max - prefix && current[current.length - 1 - suffix] === target[target.length - 1 - suffix]) {
      suffix++;
    }
    return [
      ...this.delete(prefix, current.length - prefix - suffix),
      ...this.insert(prefix, target.slice(prefix, target.length - suffix)),
    ];
  }

  // ---- cursor anchoring ----

  /** Id of the visible char just before `pos`, or null at the start. Survives remote edits. */
  anchorAt(pos: number): Id | null {
    return pos === 0 ? null : this.visibleNode(pos - 1).id;
  }

  /** Visible position right after `anchor`; if it was deleted, after its nearest visible predecessor. */
  positionOf(anchor: Id | null): number {
    if (anchor === null) return 0;
    const target = key(anchor);
    let pos = 0;
    for (let n = this.head.next; n !== null; n = n.next) {
      if (!n.deleted) pos++;
      if (key(n.id) === target) return pos;
    }
    return pos;
  }

  private visibleNode(pos: number): Node {
    let seen = -1;
    for (let n = this.head.next; n !== null; n = n.next) {
      if (!n.deleted && ++seen === pos) return n;
    }
    throw new RangeError(`position ${pos} out of range`);
  }

  // ---- views ----

  text(): string {
    let out = '';
    for (let n = this.head.next; n !== null; n = n.next) if (!n.deleted) out += n.value;
    return out;
  }

  nodes(): NodeState[] {
    const out: NodeState[] = [];
    for (let n = this.head.next; n !== null; n = n.next) out.push({ id: n.id, value: n.value, deleted: n.deleted });
    return out;
  }
}
