// Ethic Messenger app — data layer: API, realtime socket, state, sounds, noise suppression.
// UI lives in app.js and reacts to `bus` events.

export const API = (() => {
  try { return localStorage.getItem('ethic.api') || 'https://176-124-220-95.sslip.io:8443'; } catch { return 'https://176-124-220-95.sslip.io:8443'; }
})();
export const WS_URL = API.replace(/^http/, 'ws') + '/ws';
export const abs = (u) => (!u ? u : /^(https?:|blob:|data:)/.test(u) ? u : API + u);

export const bus = new EventTarget();
export const emit = (type, detail) => bus.dispatchEvent(new CustomEvent(type, { detail }));
export const on = (type, fn) => { const h = (e) => fn(e.detail); bus.addEventListener(type, h); return () => bus.removeEventListener(type, h); };

export const store = {
  get(k, d) { try { const v = localStorage.getItem(`ethic.${k}`); return v === null ? d : v; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(`ethic.${k}`, v); } catch {} },
  del(k) { try { localStorage.removeItem(`ethic.${k}`); } catch {} },
};

// Native plugins (present only inside the Capacitor app).
export const Native = {
  get on() { return !!window.Capacitor?.isNativePlatform?.(); },
  platform: () => window.Capacitor?.getPlatform?.() || 'web',
  plugin: (name) => window.Capacitor?.Plugins?.[name],
  haptic(style = 'LIGHT') {
    const h = Native.plugin('Haptics');
    if (h) h.impact({ style }).catch(() => {}); else navigator.vibrate?.(10);
  },
  openExternal(url) {
    const app = Native.plugin('App');
    if (Native.on) window.open(url, '_system'); else window.open(url, '_blank', 'noopener');
    return app;
  },
};

// ---------- state ----------
export const S = {
  token: store.get('token', null),
  me: null,
  chats: new Map(),
  users: new Map(),
  messages: new Map(), // chatId -> { list, hasMore }
  typing: new Map(), // chatId -> Map(userId -> until)
  ws: null,
  active: null, // chat open on screen
  version: null,
};

export function rememberUsers(list) {
  for (const u of list) {
    if (!u) continue;
    S.users.set(u.id, { ...S.users.get(u.id), ...u });
    if (S.me && u.id === S.me.id) S.me = { ...S.me, ...u };
  }
}
export const fresh = (u) => (u && S.users.get(u.id)) || u;
export const userName = (id) => (id === S.me?.id ? S.me.displayName : S.users.get(id)?.displayName) || 'Пользователь';
export const otherMember = (chat) => chat.members.find((m) => m.id !== S.me.id) || chat.members[0];
export const chatName = (chat) => chat.type === 'group' ? chat.title : (fresh(otherMember(chat))?.displayName || 'Чат');
export const byRecent = (a, b) => (b.lastMessage?.createdAt ?? b.createdAt) - (a.lastMessage?.createdAt ?? a.createdAt);
export const sortedChats = () => [...S.chats.values()].sort(byRecent);
export const totalUnread = () => [...S.chats.values()].reduce((n, c) => n + (c.id === S.active ? 0 : c.unread || 0), 0);

// ---------- http ----------
export async function api(path, { method = 'GET', body } = {}) {
  let res;
  try {
    res = await fetch(API + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(S.token ? { Authorization: `Bearer ${S.token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch { throw new Error('Нет соединения с сервером'); }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && S.me) { emit('logout'); throw new Error('Сессия истекла'); }
  if (!res.ok) throw new Error(data.error || `Ошибка ${res.status}`);
  return data;
}
export function upload(blob, { kind = 'image', name = '', onProgress } = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API}/api/upload?kind=${kind}&name=${encodeURIComponent(name)}`);
    xhr.setRequestHeader('Authorization', `Bearer ${S.token}`);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress?.(e.loaded / e.total); };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch {}
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new Error(data.error || `Ошибка ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('Нет соединения'));
    xhr.send(blob);
  });
}

// ---------- session ----------
export async function login(mode, fields) {
  const data = await api(`/api/${mode}`, { method: 'POST', body: fields });
  S.token = data.token;
  store.set('token', data.token);
  await start(data.user);
}
export async function restore() {
  if (!S.token) return false;
  try {
    const { user } = await api('/api/me');
    await start(user);
    return true;
  } catch { store.del('token'); S.token = null; return false; }
}
async function start(user) {
  S.me = user;
  rememberUsers([user]);
  await loadChats();
  connectWs();
}
export async function logout() {
  try { if (S.token) await fetch(`${API}/api/logout`, { method: 'POST', headers: { Authorization: `Bearer ${S.token}` } }); } catch {}
  store.del('token');
  S.token = null;
  S.ws?.close();
  location.reload();
}
export async function loadChats() {
  const { chats } = await api('/api/chats');
  S.chats.clear();
  for (const c of chats) { S.chats.set(c.id, c); rememberUsers(c.members); }
  emit('chats');
}
export function upsertChat(chat) {
  S.chats.set(chat.id, chat);
  rememberUsers(chat.members);
  emit('chats');
  emit('chat', chat.id);
}
export async function openDirect(userId) {
  const { chat } = await api('/api/chats/direct', { method: 'POST', body: { userId } });
  upsertChat(chat);
  return chat;
}
export async function loadMessages(chatId, before) {
  const data = await api(`/api/chats/${chatId}/messages${before ? `?before=${before}` : ''}`);
  const state = S.messages.get(chatId);
  if (before && state) { state.list = [...data.messages, ...state.list]; state.hasMore = data.hasMore; }
  else S.messages.set(chatId, { list: data.messages, hasMore: data.hasMore });
  return S.messages.get(chatId);
}
export function markRead(chatId) {
  const chat = S.chats.get(chatId);
  const list = S.messages.get(chatId)?.list;
  if (!chat || !list?.length || document.visibilityState !== 'visible') return;
  const last = [...list].reverse().find((m) => !m.pending);
  if (!last) return;
  if (chat.unread) { chat.unread = 0; emit('chats'); }
  wsSend({ type: 'read', chatId, messageId: last.id });
}

// ---------- websocket ----------
let wsRetry = 0;
export function connectWs() {
  const ws = new WebSocket(`${WS_URL}?token=${S.token}`);
  S.ws = ws;
  ws.onopen = () => {
    if (wsRetry > 0) loadChats().then(() => { S.messages.clear(); emit('resync'); });
    wsRetry = 0;
    emit('online', true);
  };
  ws.onmessage = (e) => handle(JSON.parse(e.data));
  ws.onclose = (e) => {
    emit('online', false);
    if (e.code === 4001) { emit('toast', 'Сессия завершена'); return setTimeout(logout, 800); }
    if (!S.token) return;
    wsRetry++;
    setTimeout(connectWs, Math.min(1000 * 2 ** wsRetry, 15000));
  };
}
export function wsSend(obj) {
  if (S.ws?.readyState !== 1) return false;
  S.ws.send(JSON.stringify(obj));
  return true;
}
// Reconnect quickly when the app returns to the foreground.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && S.token && S.ws && S.ws.readyState > 1) connectWs();
});

function setTyping(chatId, userId, on) {
  if (!S.typing.has(chatId)) S.typing.set(chatId, new Map());
  const m = S.typing.get(chatId);
  if (on) {
    m.set(userId, Date.now() + 4000);
    setTimeout(() => { if ((m.get(userId) || 0) <= Date.now()) { m.delete(userId); emit('typing', chatId); } }, 4100);
  } else m.delete(userId);
  emit('typing', chatId);
}
export function typingNames(chatId) {
  const m = S.typing.get(chatId);
  if (!m) return [];
  const now = Date.now();
  return [...m.entries()].filter(([, t]) => t > now).map(([uid]) => userName(uid));
}

function handle(ev) {
  switch (ev.type) {
    case 'hello':
      if (!S.version) S.version = ev.version;
      return;
    case 'message': return onMessage(ev.message, ev.clientId);
    case 'message:update': {
      const st = S.messages.get(ev.message.chatId);
      const i = st?.list.findIndex((x) => x.id === ev.message.id) ?? -1;
      if (i >= 0) st.list[i] = { ...ev.message, localUrl: st.list[i].localUrl };
      const chat = S.chats.get(ev.message.chatId);
      if (chat?.lastMessage?.id === ev.message.id) chat.lastMessage = ev.message;
      emit('messages', ev.message.chatId); emit('chats');
      return;
    }
    case 'message:delete': {
      const st = S.messages.get(ev.chatId);
      if (st) {
        st.list = st.list.filter((m) => m.id !== ev.messageId);
        for (const m of st.list) if (m.replyTo?.id === ev.messageId) m.replyTo = { id: ev.messageId, deleted: true };
      }
      const chat = S.chats.get(ev.chatId);
      if (chat) chat.lastMessage = ev.lastMessage;
      emit('messages', ev.chatId); emit('chats');
      return;
    }
    case 'read': {
      const chat = S.chats.get(ev.chatId);
      if (!chat || ev.userId === S.me.id) return;
      chat.othersRead = Math.max(chat.othersRead || 0, ev.messageId);
      emit('read', ev.chatId);
      return;
    }
    case 'chat': return upsertChat(ev.chat);
    case 'user':
      rememberUsers([ev.user]);
      for (const c of S.chats.values()) c.members = c.members.map((m) => (m.id === ev.user.id ? { ...m, ...ev.user } : m));
      emit('users'); emit('chats');
      return;
    case 'presence':
      rememberUsers([{ id: ev.userId, online: ev.online, lastSeen: ev.lastSeen }]);
      emit('users'); emit('chats');
      return;
    case 'typing': return setTyping(ev.chatId, ev.userId, true);
    case 'call:ring': return emit('ring', ev);
    case 'call:state': {
      const chat = S.chats.get(ev.chatId);
      if (chat) chat.call = ev.call;
      emit('callstate', ev); emit('chats');
      return;
    }
    case 'call:declined': return emit('declined', ev);
    case 'call:invited':
      if (ev.user) rememberUsers([ev.user]);
      return emit('invited', ev);
  }
}

function onMessage(m, clientId) {
  const chat = S.chats.get(m.chatId);
  if (!chat) { loadChats(); return; }
  const st = S.messages.get(m.chatId);
  const mine = m.userId === S.me.id;
  if (st) {
    const i = clientId ? st.list.findIndex((x) => x.clientId === clientId) : -1;
    if (i >= 0) st.list[i] = { ...m, localUrl: st.list[i].localUrl, freshAt: st.list[i].freshAt };
    else st.list.push({ ...m, freshAt: Date.now() });
  }
  chat.lastMessage = m;
  setTyping(m.chatId, m.userId, false);
  const visible = m.chatId === S.active && document.visibilityState === 'visible';
  if (!mine) {
    Sound.received();
    if (!visible) chat.unread = (chat.unread || 0) + 1;
  }
  emit('messages', m.chatId);
  emit('chats');
  if (visible) markRead(m.chatId);
}

// ---------- sending ----------
export function pushPending(chatId, replyTo, extra) {
  const clientId = Math.random().toString(36).slice(2);
  const r = replyTo;
  const pending = {
    id: `p-${clientId}`, clientId, chatId, userId: S.me.id, text: '', createdAt: Date.now(), pending: true, freshAt: Date.now(),
    replyTo: r ? { id: r.id, userId: r.userId, text: (r.text || '').slice(0, 140), kind: r.image ? 'photo' : r.file?.kind || null, fileName: r.file?.name } : null,
    ...extra,
  };
  S.messages.get(chatId)?.list.push(pending);
  emit('messages', chatId);
  return pending;
}
function dropPending(chatId, clientId) {
  const list = S.messages.get(chatId)?.list;
  const i = list?.findIndex((m) => m.clientId === clientId) ?? -1;
  if (i >= 0) list.splice(i, 1);
  emit('messages', chatId);
}
export function sendText(chatId, text, replyTo) {
  const p = pushPending(chatId, replyTo, { text });
  Sound.sent();
  wsSend({ type: 'message', chatId, text, clientId: p.clientId, replyTo: replyTo?.id });
}
export async function sendUpload(chatId, blob, { kind, name, text = '', replyTo, pendingExtra, build }) {
  const p = pushPending(chatId, replyTo, { text, progress: 0, ...pendingExtra });
  Sound.sent();
  try {
    const res = await upload(blob, {
      kind, name,
      onProgress: (pr) => { p.progress = pr; emit('progress', p); },
    });
    wsSend({ type: 'message', chatId, clientId: p.clientId, text, replyTo: replyTo?.id, ...build(res) });
  } catch (err) {
    emit('toast', `Не отправлено: ${err.message}`);
    dropPending(chatId, p.clientId);
  }
}

// ---------- images ----------
const loadImage = (url) => new Promise((ok, fail) => { const img = new Image(); img.onload = () => ok(img); img.onerror = fail; img.src = url; });
const canvasBlob = (canvas, type, q) => new Promise((ok) => canvas.toBlob(ok, type, q));
export async function encodeCanvas(canvas) {
  let blob = await canvasBlob(canvas, 'image/webp', 0.86);
  if (!blob || blob.type !== 'image/webp') blob = await canvasBlob(canvas, 'image/jpeg', 0.88);
  return blob;
}
export const isImageFile = (f) => f.type.startsWith('image/') || (!f.type && /\.(jpe?g|png|gif|webp|bmp|avif|heic|heif)$/i.test(f.name));
export async function decodeImage(file) {
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return { src: bmp, w: bmp.width, h: bmp.height };
  } catch {}
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    return { src: img, w: img.naturalWidth, h: img.naturalHeight };
  } catch {
    throw new Error('Не удалось открыть изображение');
  } finally { URL.revokeObjectURL(url); }
}
export async function prepareImage(file) {
  const { src, w, h } = await decodeImage(file);
  const MAX = 2560;
  if (file.type === 'image/gif' || (Math.max(w, h) <= MAX && file.size <= 1.5 * 1024 * 1024 && /jpeg|png|webp/.test(file.type))) {
    return { blob: file, w, h, url: URL.createObjectURL(file) };
  }
  const scale = Math.min(1, MAX / Math.max(w, h));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(w * scale);
  canvas.height = Math.round(h * scale);
  canvas.getContext('2d').drawImage(src, 0, 0, canvas.width, canvas.height);
  const blob = await encodeCanvas(canvas);
  if (!blob) throw new Error('Не удалось обработать изображение');
  return { blob, w: canvas.width, h: canvas.height, url: URL.createObjectURL(blob) };
}
// Center-crop to aspect (w/h) and encode; used for avatars (1) and banners (3).
export async function cropTo(file, aspect, maxW) {
  const { src, w, h } = await decodeImage(file);
  let sw = w, sh = Math.round(w / aspect);
  if (sh > h) { sh = h; sw = Math.round(h * aspect); }
  const canvas = document.createElement('canvas');
  canvas.width = Math.min(maxW, sw);
  canvas.height = Math.round(canvas.width / aspect);
  canvas.getContext('2d').drawImage(src, (w - sw) / 2, (h - sh) / 2, sw, sh, 0, 0, canvas.width, canvas.height);
  const blob = await encodeCanvas(canvas);
  if (!blob) throw new Error('Не удалось обработать изображение');
  return blob;
}

// ---------- sounds ----------
export const Sound = (() => {
  let ctx = null;
  const getCtx = () => {
    if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    return ctx;
  };
  function note(c, at, freq, dur, { type = 'sine', vol = 0.15, to = null, attack = 0.015 } = {}) {
    const o = c.createOscillator(), g = c.createGain();
    o.type = type;
    o.frequency.setValueAtTime(freq, at);
    if (to) o.frequency.exponentialRampToValueAtTime(to, at + dur);
    g.gain.setValueAtTime(0.0001, at);
    g.gain.exponentialRampToValueAtTime(vol, at + attack);
    g.gain.exponentialRampToValueAtTime(0.0001, at + dur);
    o.connect(g).connect(c.destination);
    o.start(at);
    o.stop(at + dur + 0.05);
  }
  const once = (fn) => { try { const c = getCtx(); fn(c, c.currentTime + 0.02); } catch {} };
  const RINGTONES = {
    classic: { name: 'Классика', period: 2000, play(c, t) { note(c, t, 880, 0.22); note(c, t + 0.25, 660, 0.22); } },
    marimba: { name: 'Маримба', period: 2400, play(c, t) { [659, 784, 988, 1319, 988, 1319].forEach((f, i) => note(c, t + i * 0.13, f, 0.35, { type: 'triangle', vol: 0.2, attack: 0.005 })); } },
    soft: { name: 'Нежный', period: 2600, play(c, t) { [523, 659, 784].forEach((f) => note(c, t, f, 1.1, { vol: 0.07, attack: 0.25 })); } },
    digital: { name: 'Цифровой', period: 1600, play(c, t) { for (let i = 0; i < 4; i++) note(c, t + i * 0.12, 1250, 0.07, { type: 'square', vol: 0.05, attack: 0.003 }); } },
    retro: { name: 'Старый телефон', period: 3000, play(c, t) { for (let i = 0; i < 20; i++) note(c, t + i * 0.05, i % 2 ? 480 : 440, 0.05, { type: 'square', vol: 0.04, attack: 0.002 }); } },
    none: { name: 'Без звука', period: 2000, play() {} },
  };
  let loop = null;
  const stop = () => { clearInterval(loop); loop = null; };
  const startLoop = (fn, period) => {
    stop();
    try { const c = getCtx(); fn(c, c.currentTime + 0.02); loop = setInterval(() => fn(c, c.currentTime + 0.02), period); } catch {}
  };
  const msgSounds = () => store.get('msgSound', '1') === '1';
  return {
    RINGTONES,
    unlock: () => { try { getCtx(); } catch {} },
    ring(id = store.get('ringtone', 'classic')) { const r = RINGTONES[id] || RINGTONES.classic; startLoop((c, t) => r.play(c, t), r.period); },
    preview(id) { stop(); once((c, t) => (RINGTONES[id] || RINGTONES.classic).play(c, t)); },
    ringback() { startLoop((c, t) => note(c, t, 425, 1.0, { vol: 0.06, attack: 0.02 }), 4000); },
    stop,
    join() { once((c, t) => { note(c, t, 587, 0.16, { type: 'triangle', vol: 0.14, attack: 0.005 }); note(c, t + 0.11, 880, 0.24, { type: 'triangle', vol: 0.14, attack: 0.005 }); }); },
    leave() { once((c, t) => { note(c, t, 880, 0.16, { type: 'triangle', vol: 0.12, attack: 0.005 }); note(c, t + 0.11, 523, 0.26, { type: 'triangle', vol: 0.12, attack: 0.005 }); }); },
    sent() { if (msgSounds()) once((c, t) => note(c, t, 320, 0.14, { vol: 0.07, to: 1400, attack: 0.01 })); },
    received() { if (msgSounds()) once((c, t) => { note(c, t, 988, 0.12, { type: 'triangle', vol: 0.12, attack: 0.004 }); note(c, t + 0.09, 1319, 0.18, { type: 'triangle', vol: 0.1, attack: 0.004 }); }); },
  };
})();

// ---------- noise suppression (RNNoise bundled in the app) ----------
export const NS = {
  lib: null, wasm: null,
  mode: () => store.get('noise', 'standard'),
  constraints() {
    const on = NS.mode() !== 'off';
    return { echoCancellation: true, noiseSuppression: on, autoGainControl: on, channelCount: 1 };
  },
  async load() {
    if (!this.lib) this.lib = await import('../vendor/ns/index.js');
    if (!this.wasm) this.wasm = await this.lib.loadRnnoise({ url: 'vendor/ns/rnnoise.wasm', simdUrl: 'vendor/ns/rnnoise_simd.wasm' });
  },
  async wrap(raw) {
    await this.load();
    const ctx = new AudioContext({ sampleRate: 48000 });
    await ctx.audioWorklet.addModule('vendor/ns/rnnoise/workletProcessor.js');
    const src = ctx.createMediaStreamSource(new MediaStream([raw]));
    const node = new this.lib.RnnoiseWorkletNode(ctx, { wasmBinary: this.wasm, maxChannels: 1 });
    const dest = ctx.createMediaStreamDestination();
    src.connect(node).connect(dest);
    if (ctx.state === 'suspended') await ctx.resume().catch(() => {});
    return {
      track: dest.stream.getAudioTracks()[0],
      stop() { try { src.disconnect(); node.disconnect(); node.destroy?.(); } catch {} ctx.close().catch(() => {}); raw.stop(); },
    };
  },
  async micStream() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: this.constraints() });
    const stopRaw = () => stream.getTracks().forEach((t) => t.stop());
    if (this.mode() !== 'strong') return { stream, stop: stopRaw };
    try {
      const w = await this.wrap(stream.getAudioTracks()[0]);
      return { stream: new MediaStream([w.track]), stop: () => w.stop() };
    } catch (e) { console.error('RNNoise unavailable', e); return { stream, stop: stopRaw }; }
  },
};

export function mediaError(e, what) {
  if (e?.name === 'NotAllowedError') return `Нет доступа к ${what}. Разрешите его в настройках телефона для Ethic Messenger`;
  if (e?.name === 'NotFoundError' || e?.name === 'OverconstrainedError') return `Устройство не найдено (${what})`;
  if (e?.name === 'NotReadableError' || e?.name === 'AbortError') return `Устройство занято другим приложением (${what})`;
  return `Ошибка доступа к ${what}`;
}
