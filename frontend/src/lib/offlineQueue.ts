"use client";
import { api, ApiError } from "./api";

/** Attendance events captured on the device; survive reloads; synced in order via the batch endpoint. */
export type QueuedEvent = {
  clientEventId: string;
  kind: "CHECK_IN" | "CHECK_OUT";
  occurredAt: string;
  assignmentId?: string;
  latitude?: number;
  longitude?: number;
  accuracyM?: number;
  deviceId: string;
  offline?: boolean;
  state: "pending" | "synced" | "rejected" | "flagged";
  message?: string;
  label?: string;
};

const KEY = "erp.guard.queue";

export function loadQueue(): QueuedEvent[] {
  try { return JSON.parse(localStorage.getItem(KEY) || "[]"); } catch { return []; }
}

export function saveQueue(q: QueuedEvent[]) {
  try { localStorage.setItem(KEY, JSON.stringify(q.slice(-100))); } catch { /* storage full/blocked */ }
}

export function deviceId(): string {
  try {
    let d = localStorage.getItem("erp.device");
    if (!d) { d = "web-" + Math.random().toString(36).slice(2, 10); localStorage.setItem("erp.device", d); }
    return d;
  } catch {
    return "web-unknown";
  }
}

function toBody(e: QueuedEvent) {
  const { state: _s, message: _m, label: _l, ...body } = e;
  return body;
}

function applyResult(e: QueuedEvent, r: any): QueuedEvent {
  const state = r.status === "REJECTED" ? "rejected" : r.status === "FLAGGED" ? "flagged" : "synced";
  const msg = r.message ?? (r.flags?.length ? r.flags.join(", ") : undefined);
  return { ...e, state, message: msg };
}

/** Try to send one event online; if the network fails it stays pending in the queue. */
export async function submitEvent(e: QueuedEvent): Promise<QueuedEvent> {
  try {
    const r = await api.post("/v1/attendance/events", toBody(e), { idem: e.clientEventId });
    return applyResult(e, r);
  } catch (err) {
    const a = err as ApiError;
    if (a.status === 0) return { ...e, state: "pending", offline: true, message: "Saved offline — will sync" };
    return { ...e, state: "rejected", message: a.message };
  }
}

/** Upload all pending events (ordered) through the offline batch endpoint. */
export async function syncPending(q: QueuedEvent[]): Promise<QueuedEvent[]> {
  const pending = q.filter((e) => e.state === "pending");
  if (!pending.length) return q;
  const results: any[] = await api.post("/v1/attendance/events/batch", pending.map((e) => ({ ...toBody(e), offline: true })));
  const byId = new Map(results.map((r) => [r.clientEventId, r]));
  return q.map((e) => (e.state === "pending" && byId.has(e.clientEventId) ? applyResult(e, byId.get(e.clientEventId)) : e));
}
