import { Client, type StompSubscription } from '@stomp/stompjs';
import { api, fromDto, toDto, type OpBatch, type Participant } from './api';
import { Rga, type Op } from './crdt/rga';

// ~60 bytes per op on the wire, so a full batch is ~120 KB, well under the server's 1 MB frame limit.
const BATCH_SIZE = 2_000;

export type Status = 'connecting' | 'online' | 'offline';

export interface SessionEvents {
  /** The replica changed because of remote ops or a reload; re-render from `session.rga`. */
  onRemoteChange(): void;
  onPresence(people: Participant[]): void;
  onStatus(status: Status): void;
  onTitle(title: string): void;
  /** The document was deleted while open. */
  onDeleted(): void;
}

/**
 * One browser tab's connection to one document.
 *
 * Local ops live in `outbox` until sent, then in `inflight` until the server echoes the batch back
 * (the server echoes every batch to its sender, in order). If the connection drops, nothing is
 * lost: on reconnect we subscribe, fetch the server's full CRDT state, and re-apply every
 * unconfirmed op. Ops the server already has are no-ops; the rest are resent. Remote ops that
 * arrive during the fetch are buffered and applied afterwards. Applying ops is idempotent, so
 * overlaps are harmless.
 */
export class DocSession {
  readonly site = crypto.randomUUID().slice(0, 8);
  rga = new Rga(this.site);
  private outbox: Op[] = [];
  private inflight: Op[][] = [];
  private loaded = false;
  private buffered: Op[] = [];
  private readonly client: Client;
  private subs: StompSubscription[] = [];
  private readonly docId: string;
  private readonly me: { name: string; color: string };
  private readonly events: SessionEvents;

  constructor(docId: string, me: { name: string; color: string }, events: SessionEvents) {
    this.docId = docId;
    this.me = me;
    this.events = events;
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    this.client = new Client({
      brokerURL: `${scheme}://${location.host}/ws`,
      reconnectDelay: 2000,
      onConnect: () => void this.handleConnect(),
      onWebSocketClose: () => {
        this.loaded = false;
        this.events.onStatus('offline');
      },
    });
    this.events.onStatus('connecting');
    this.client.activate();
  }

  /** Records local ops (already applied to `rga`) and sends them when connected. */
  sendLocal(ops: Op[]) {
    if (ops.length === 0) return;
    this.outbox.push(...ops);
    this.flush();
  }

  /** Number of local ops the server has not confirmed yet. */
  get unconfirmed(): number {
    return this.outbox.length + this.inflight.reduce((n, batch) => n + batch.length, 0);
  }

  dispose() {
    void this.client.deactivate();
  }

  private async handleConnect() {
    this.subs.forEach((s) => s.unsubscribe());
    this.loaded = false;
    this.buffered = [];
    this.subs = [
      this.client.subscribe(`/topic/doc/${this.docId}`, (msg) => {
        const batch: OpBatch = JSON.parse(msg.body);
        if (batch.site === this.site) {
          if (this.loaded) this.inflight.shift();
          return;
        }
        const ops = batch.ops.map(fromDto);
        if (!this.loaded) {
          this.buffered.push(...ops);
          return;
        }
        ops.forEach((op) => this.rga.apply(op));
        this.events.onRemoteChange();
      }),
      this.client.subscribe(`/topic/doc/${this.docId}/presence`, (msg) => this.events.onPresence(JSON.parse(msg.body))),
      this.client.subscribe(`/topic/doc/${this.docId}/deleted`, () => this.events.onDeleted()),
    ];

    try {
      const { title, nodes } = await api.load(this.docId);
      const fresh = Rga.fromNodes(this.site, nodes);
      // Keep only the local ops the server never received; those are resent below.
      this.outbox = [...this.inflight.flat(), ...this.outbox].filter((op) => fresh.apply(op));
      this.inflight = [];
      this.buffered.forEach((op) => fresh.apply(op));
      this.buffered = [];
      this.rga = fresh;
      this.loaded = true;
      this.events.onTitle(title);
      this.events.onRemoteChange();
      this.client.publish({ destination: `/app/doc/${this.docId}/join`, body: JSON.stringify(this.me) });
      this.events.onStatus('online');
      this.flush();
    } catch (e) {
      // A 404 here means the document was deleted while this tab was disconnected.
      if (String(e).includes(': 404')) this.events.onDeleted();
      else this.events.onStatus('offline');
    }
  }

  private flush() {
    if (!this.loaded || !this.client.connected) return;
    // Chunk large pastes so each frame stays well under the server's message size limit.
    while (this.outbox.length > 0) {
      const chunk = this.outbox.splice(0, BATCH_SIZE);
      const batch: OpBatch = { site: this.site, ops: chunk.map(toDto) };
      this.client.publish({ destination: `/app/doc/${this.docId}/ops`, body: JSON.stringify(batch) });
      this.inflight.push(chunk);
    }
  }
}
