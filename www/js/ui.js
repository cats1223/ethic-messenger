// Shared UI helpers for the app: formatting, avatars, icons and Ionic overlays.
import { S, abs, fresh } from './core.js';

export const MODE = window.Ionic?.config?.mode || 'md';
export const $ = (root, s) => root.querySelector(s);
export const $$ = (root, s) => [...root.querySelectorAll(s)];
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const linkify = (s) => esc(s).replace(/\bhttps?:\/\/[^\s<]+/g, (u) => `<a href="${u}" data-ext="${u}">${u}</a>`);

// ---------- time ----------
const pad = (n) => String(n).padStart(2, '0');
export const hhmm = (t) => { const d = new Date(t); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
export const dayKey = (t) => new Date(t).toDateString();
const isToday = (t) => dayKey(t) === new Date().toDateString();
const isYesterday = (t) => { const y = new Date(); y.setDate(y.getDate() - 1); return dayKey(t) === y.toDateString(); };
export function listTime(t) {
  if (!t) return '';
  if (isToday(t)) return hhmm(t);
  if (isYesterday(t)) return 'вчера';
  const d = new Date(t);
  if (Date.now() - t < 6 * 86400e3) return d.toLocaleDateString('ru-RU', { weekday: 'short' });
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${String(d.getFullYear()).slice(2)}`;
}
export function dayLabel(t) {
  if (isToday(t)) return 'Сегодня';
  if (isYesterday(t)) return 'Вчера';
  const d = new Date(t);
  return d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: d.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
}
export function lastSeenText(u) {
  if (!u) return '';
  if (u.online) return 'в сети';
  const t = u.lastSeen;
  if (!t) return 'не в сети';
  const mins = Math.floor((Date.now() - t) / 60000);
  if (mins < 1) return 'был(а) только что';
  if (mins < 60) return `был(а) ${mins} мин. назад`;
  if (isToday(t)) return `был(а) сегодня в ${hhmm(t)}`;
  if (isYesterday(t)) return `был(а) вчера в ${hhmm(t)}`;
  return `был(а) ${new Date(t).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}`;
}
export const fmtSize = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} МБ` : b >= 1024 ? `${Math.round(b / 1024)} КБ` : `${b} Б`);
export const fmtDur = (s) => { s = Math.max(0, Math.round(s || 0)); return `${Math.floor(s / 60)}:${pad(s % 60)}`; };

// ---------- avatars & badges ----------
const COLORS = ['#3f3f46', '#52525b', '#27272a', '#404040', '#2f2f2f', '#4b4b4b', '#363636', '#5a5a5a'];
export const colorOf = (id) => COLORS[Math.abs(id) % COLORS.length];
const initials = (name) => String(name || '?').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
export function avatarRaw(name, id, url, { group = false, dot = false, cls = '' } = {}) {
  const inner = url ? `<img src="${esc(abs(url))}" alt="" loading="lazy">` : esc(initials(name));
  return `<div class="av ${group ? 'group' : ''} ${cls}" style="background:${url ? '#111' : colorOf(id)}">${inner}${dot ? '<span class="dot"></span>' : ''}</div>`;
}
export const avatarOf = (u, opts = {}) => {
  const f = fresh(u) || {};
  return avatarRaw(f.displayName, f.id || 0, f.avatar, { ...opts, dot: opts.dot && f.online });
};
export const groupAvatar = (chat, opts = {}) => avatarRaw(chat.title, chat.id + 3, null, { ...opts, group: true });
export const CHECK = '<span class="check"><svg viewBox="0 0 24 24"><path fill="#1d9bf0" d="M12 1.5l2.6 1.9 3.2-.1 1 3 2.6 1.9-1 3.1 1 3.1-2.6 1.9-1 3-3.2-.1L12 22.5l-2.6-1.9-3.2.1-1-3-2.6-1.9 1-3.1-1-3.1 2.6-1.9 1-3 3.2.1z"/><path d="m7.8 12.3 2.8 2.8 5.6-5.8" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>';
export const checkOf = (u) => (fresh(u)?.verified ? CHECK : '');
export const badgeOf = (u) => { const f = fresh(u); return f?.badge ? `<span class="badge-tag ${f.role === 'owner' ? 'owner' : ''}">${esc(f.badge)}</span>` : ''; };
export const DEFAULT_BANNER = 'linear-gradient(135deg, #2a2a2a, #000000)';
export const bannerStyle = (u) => {
  const b = fresh(u)?.banner;
  if (!b) return `background:${DEFAULT_BANNER}`;
  if (b.startsWith('#')) return `background:${b}`;
  return `background:#1a1a1a url('${abs(b).replace(/['"\\()<>\s]/g, '')}') center/cover`;
};
export function previewText(m) {
  if (!m) return '';
  if (m.image) return m.text ? `📷 ${m.text}` : '📷 Фото';
  if (m.file?.kind === 'voice') return '🎤 Голосовое сообщение';
  if (m.file?.kind === 'video') return m.text ? `🎬 ${m.text}` : '🎬 Видео';
  if (m.file) return `📎 ${m.text || m.file.name}`;
  return m.text;
}
export const replyKindText = (r) => (r.kind === 'photo' ? 'Фото' : r.kind === 'voice' ? 'Голосовое сообщение' : r.kind === 'video' ? 'Видео' : r.kind ? (r.fileName || 'Файл') : '');

// ---------- icons ----------
const ic = (d, fill = false) => fill
  ? `<svg viewBox="0 0 24 24" fill="currentColor">${d}</svg>`
  : `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
export const I = {
  chats: ic('<path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-3.9-.9L3 21l1.9-5.1A8.4 8.4 0 1 1 21 11.5z"/>'),
  people: ic('<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8"/>'),
  gear: ic('<path d="M12.2 2h-.4a2 2 0 0 0-2 2v.2a2 2 0 0 1-1 1.7l-.4.3a2 2 0 0 1-2 0l-.2-.1a2 2 0 0 0-2.7.7l-.2.4a2 2 0 0 0 .7 2.7l.2.1a2 2 0 0 1 1 1.7v.6a2 2 0 0 1-1 1.7l-.2.1a2 2 0 0 0-.7 2.7l.2.4a2 2 0 0 0 2.7.7l.2-.1a2 2 0 0 1 2 0l.4.3a2 2 0 0 1 1 1.7v.2a2 2 0 0 0 2 2h.4a2 2 0 0 0 2-2v-.2a2 2 0 0 1 1-1.7l.4-.3a2 2 0 0 1 2 0l.2.1a2 2 0 0 0 2.7-.7l.2-.4a2 2 0 0 0-.7-2.7l-.2-.1a2 2 0 0 1-1-1.7v-.6a2 2 0 0 1 1-1.7l.2-.1a2 2 0 0 0 .7-2.7l-.2-.4a2 2 0 0 0-2.7-.7l-.2.1a2 2 0 0 1-2 0l-.4-.3a2 2 0 0 1-1-1.7V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>'),
  compose: ic('<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>'),
  group: ic('<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M19 8v6M22 11h-6"/>'),
  userPlus: ic('<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M19 8v6M22 11h-6"/>'),
  phone: ic('<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z"/>'),
  video: ic('<path d="m22 8-6 4 6 4V8z"/><rect x="2" y="6" width="14" height="12" rx="2"/>'),
  videoOff: ic('<path d="M10.7 6H14a2 2 0 0 1 2 2v3.3l1 1L22 8v8"/><path d="M16 16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2"/><path d="m2 2 20 20"/>'),
  mic: ic('<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M19 10v1a7 7 0 0 1-14 0v-1M12 18v4"/>'),
  micOff: ic('<path d="m2 2 20 20"/><path d="M18.9 13.4A7 7 0 0 0 19 11v-1M5 10v1a7 7 0 0 0 11.5 5.4M15 9.3V5a3 3 0 0 0-5.7-1.3M9 9v2a3 3 0 0 0 5.1 2.1M12 18v4"/>'),
  hangup: ic('<path d="M10.7 13.3a16 16 0 0 0 3.4 2.6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2v3a2 2 0 0 1-2.2 2A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8" transform="rotate(135 12 12)"/>'),
  send: ic('<path d="M12 19V5M5 12l7-7 7 7"/>'),
  plus: ic('<path d="M12 5v14M5 12h14"/>'),
  x: ic('<path d="M18 6 6 18M6 6l12 12"/>'),
  trash: ic('<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>'),
  file: ic('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>'),
  play: ic('<path d="M7 4v16l13-8z"/>', true),
  pause: ic('<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>', true),
  check: ic('<path d="M20 6 9 17l-5-5"/>'),
  checks: ic('<path d="M18 6 7 17l-5-5"/><path d="m22 10-7.5 7.5L13 16"/>'),
  clock: ic('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'),
  flip: ic('<path d="M20 7h-3.2l-1.6-2.4A1.4 1.4 0 0 0 14 4h-4a1.4 1.4 0 0 0-1.2.6L7.2 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z"/><path d="M9 13.5a3 3 0 0 1 5.2-2M15 12.5a3 3 0 0 1-5.2 2M14.2 9.5v2h-2M9.8 16.5v-2h2"/>'),
  speaker: ic('<path d="M11 5 6 9H2v6h4l5 4V5z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"/>'),
  earpiece: ic('<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 5h2"/>'),
  minimize: ic('<path d="m6 9 6 6 6-6"/>'),
  screen: ic('<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/>'),
  shield: ic('<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>'),
  globe: ic('<circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>'),
  user: ic('<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>'),
  wave: ic('<path d="M2 12h2M6 8v8M10 4v16M14 7v10M18 10v4M22 12h0"/>'),
  bell: ic('<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>'),
  music: ic('<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>'),
  info: ic('<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>'),
  leave: ic('<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>'),
};

// ---------- Ionic overlays ----------
export async function toast(message, color) {
  await customElements.whenDefined('ion-toast');
  const t = document.createElement('ion-toast');
  t.message = message;
  t.duration = 2600;
  t.position = 'top';
  if (color) t.color = color;
  document.body.appendChild(t);
  t.addEventListener('didDismiss', () => t.remove());
  await t.present();
}
// Native-style action sheet; resolves with the chosen id or null.
export async function sheet(header, buttons) {
  await customElements.whenDefined('ion-action-sheet');
  const a = document.createElement('ion-action-sheet');
  if (header) a.header = header;
  a.buttons = [...buttons.map((b) => ({ text: b.text, role: b.destructive ? 'destructive' : undefined, data: b.id })), { text: 'Отмена', role: 'cancel' }];
  document.body.appendChild(a);
  await a.present();
  const { data, role } = await a.onDidDismiss();
  a.remove();
  return role === 'cancel' || role === 'backdrop' ? null : data ?? null;
}
export async function confirmDialog(header, message, okText = 'OK', destructive = false) {
  await customElements.whenDefined('ion-alert');
  const a = document.createElement('ion-alert');
  a.header = header;
  if (message) a.message = message;
  a.buttons = [{ text: 'Отмена', role: 'cancel' }, { text: okText, role: destructive ? 'destructive' : 'confirm' }];
  document.body.appendChild(a);
  await a.present();
  const { role } = await a.onDidDismiss();
  a.remove();
  return role === 'confirm' || role === 'destructive';
}
export async function presentModal(el, { cls = '', card = true, onDismiss } = {}) {
  await customElements.whenDefined('ion-modal');
  const m = document.createElement('ion-modal');
  if (cls) m.className = cls;
  if (card && MODE === 'ios') m.presentingElement = document.querySelector('ion-nav');
  m.appendChild(el);
  document.body.appendChild(m);
  m.addEventListener('didDismiss', () => { onDismiss?.(); m.remove(); });
  await m.present();
  return m;
}
export function pickFile(accept = '', capture = '') {
  return new Promise((resolve) => {
    const i = document.createElement('input');
    i.type = 'file';
    if (accept) i.accept = accept;
    if (capture) i.setAttribute('capture', capture);
    i.onchange = () => resolve(i.files[0] || null);
    i.click();
  });
}
