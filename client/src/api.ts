import type { Id, NodeState, Op } from './crdt/rga';

/** Compact wire format; mirrors Dtos.OpDto on the server. */
export interface OpDto {
  t: 'i' | 'd';
  c: number;
  s: string;
  rc: number | null;
  rs: string | null;
  v: string | null;
}

export interface OpBatch {
  site: string;
  ops: OpDto[];
}

export interface Participant {
  sessionId: string;
  name: string;
  color: string;
}

export interface DocumentSummary {
  id: string;
  title: string;
  updatedAt: string;
}

export interface Version {
  seq: number;
  createdAt: string;
  preview: string;
}

interface NodeDto {
  c: number;
  s: string;
  v: string;
  d: boolean;
}

export function toDto(op: Op): OpDto {
  return op.type === 'insert'
    ? { t: 'i', c: op.id.counter, s: op.id.site, rc: op.after?.counter ?? null, rs: op.after?.site ?? null, v: op.value }
    : { t: 'd', c: op.target.counter, s: op.target.site, rc: null, rs: null, v: null };
}

export function fromDto(dto: OpDto): Op {
  const id: Id = { counter: dto.c, site: dto.s };
  if (dto.t === 'd') return { type: 'delete', target: id };
  return { type: 'insert', id, after: dto.rs === null ? null : { counter: dto.rc!, site: dto.rs }, value: dto.v! };
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init });
  if (!res.ok) throw new Error(`${init?.method ?? 'GET'} ${path} failed: ${res.status}`);
  return res.status === 204 ? (undefined as T) : res.json();
}

export const api = {
  list: () => request<DocumentSummary[]>('/api/docs'),
  create: (title: string) =>
    request<{ id: string }>('/api/docs', { method: 'POST', body: JSON.stringify({ title }) }),
  async load(id: string): Promise<{ title: string; nodes: NodeState[] }> {
    const doc = await request<{ title: string; nodes: NodeDto[] }>(`/api/docs/${id}`);
    return {
      title: doc.title,
      nodes: doc.nodes.map((n) => ({ id: { counter: n.c, site: n.s }, value: n.v, deleted: n.d })),
    };
  },
  versions: (id: string) => request<Version[]>(`/api/docs/${id}/versions`),
  version: (id: string, seq: number) => request<{ seq: number; content: string }>(`/api/docs/${id}/versions/${seq}`),
  restore: (id: string, seq: number) => request<void>(`/api/docs/${id}/versions/${seq}/restore`, { method: 'POST' }),
};
