"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, errMsg } from "./api";

/** Loads a GET endpoint; re-runs when path changes. path=null skips. */
export function useApi<T = any>(path: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(!!path);
  const seq = useRef(0);
  const reload = useCallback(async () => {
    if (!path) { setLoading(false); return; }
    const n = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const d = await api.get<T>(path);
      if (n === seq.current) setData(d);
    } catch (e) {
      if (n === seq.current) setError(errMsg(e));
    } finally {
      if (n === seq.current) setLoading(false);
    }
  }, [path]);
  useEffect(() => { reload(); }, [reload]);
  return { data, error, loading, reload, setData };
}

/** Lookup map id -> label for select inputs and table display. */
export function useLookup(path: string | null, label: (x: any) => string) {
  const { data } = useApi<any>(path);
  const items: any[] = Array.isArray(data) ? data : data?.items ?? [];
  const map: Record<string, string> = {};
  items.forEach((x) => { map[x.id] = label(x); });
  const options = items.map((x) => ({ value: x.id as string, label: label(x) }));
  return { items, map, options };
}
