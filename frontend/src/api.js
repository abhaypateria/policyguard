// Base URL of the Spring Boot API. Empty in development (Vite proxies /api to :8081);
// on Vercel it is set at build time with VITE_API_BASE=https://<user>-policyguard.hf.space
export const API_BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '');

async function request(path, { method = 'GET', body, headers = {} } = {}) {
  const isForm = body instanceof FormData;
  const res = await fetch(API_BASE + path, {
    method,
    body: isForm ? body : body !== undefined ? JSON.stringify(body) : undefined,
    headers: { ...(body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}), ...headers },
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    const err = new Error((data && data.message) || `${res.status} ${res.statusText}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

export const api = {
  status: () => request('/api/status'),
  ask: (payload, user) => request('/api/chat', { method: 'POST', body: payload, headers: { 'X-User': user } }),
  compare: (payload, user) => request('/api/chat/compare', { method: 'POST', body: payload, headers: { 'X-User': user } }),
  documents: () => request('/api/documents'),
  upload: (form, adminKey, user) =>
    request('/api/documents', { method: 'POST', body: form, headers: { 'X-Admin-Key': adminKey || '', 'X-User': user } }),
  deleteDocument: (id, adminKey) =>
    request(`/api/documents/${id}`, { method: 'DELETE', headers: { 'X-Admin-Key': adminKey || '' } }),
  audit: (page, size) => request(`/api/audit?page=${page}&size=${size}`),
  verify: () => request('/api/audit/verify'),
  head: () => request('/api/audit/head'),
  evalLatest: () => request('/api/eval/latest'),
  evalCurrent: () => request('/api/eval/current'),
  runEval: (provider, adminKey) =>
    request(`/api/eval/run${provider ? `?provider=${encodeURIComponent(provider)}` : ''}`, {
      method: 'POST',
      headers: { 'X-Admin-Key': adminKey || '' },
    }),
  fileUrl: (id, page) => `${API_BASE}/api/documents/${id}/file${page ? `#page=${page}` : ''}`,
};

export const storage = {
  get(key, fallback = '') {
    try {
      return localStorage.getItem(key) ?? fallback;
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* private mode - ignore */
    }
  },
};
