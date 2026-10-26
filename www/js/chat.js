// Chat screen: message bubbles, composer, attachments, voice messages, long-press actions.
import * as C from './core.js';
import {
  $, $$, esc, linkify, I, avatarOf, groupAvatar, checkOf, badgeOf, hhmm, dayKey, dayLabel, lastSeenText,
  fmtSize, fmtDur, replyKindText, toast, sheet, confirmDialog, presentModal, pickFile, MODE,
} from './ui.js';
import { Page, push } from './page.js';
import { startCall, joinCall, inCall } from './call.js';

const { S, on, emit, api, store, Native, Sound, abs } = C;

// ---------- voice playback (one at a time) ----------
const Voice = {
  audio: null, id: null, dur: 0,
  toggle(el) {
    const id = el.dataset.voice;
    if (this.id === id && this.audio) { if (this.audio.paused) this.audio.play().catch(() => {}); else this.audio.pause(); return; }
    this.stop();
    const a = new Audio(el.dataset.src);
    this.audio = a; this.id = id; this.dur = Number(el.dataset.dur) || 0;
    a.ontimeupdate = a.onplay = a.onpause = () => this.paint();
    a.onended = () => this.stop();
    a.play().catch(() => { toast('Не удалось воспроизвести'); this.stop(); });
  },
  seek(el, frac) {
    if (this.id !== el.dataset.voice) this.toggle(el);
    const a = this.audio;
    if (!a) return;
    const set = () => { a.currentTime = Math.max(0, Math.min(1, frac)) * (this.dur || a.duration || 0); this.paint(); };
    if (a.readyState >= 1) set(); else a.addEventListener('loadedmetadata', set, { once: true });
  },
  stop() { this.audio?.pause(); this.audio = null; this.id = null; this.paint(); },
  paint() {
    for (const el of document.querySelectorAll('[data-voice]')) {
      const active = el.dataset.voice === this.id && this.audio;
      const dur = Number(el.dataset.dur) || (active && Number.isFinite(this.audio.duration) ? this.audio.duration : 0);
      const t = active ? this.audio.currentTime : 0;
      el.querySelector('.vp').innerHTML = active && !this.audio.paused ? I.pause : I.play;
      const bars = el.querySelectorAll('.bars i');
      const on = Math.round((dur ? Math.min(1, t / dur) : 0) * bars.length);
      bars.forEach((b, i) => b.classList.toggle('on', i < on));
      el.querySelector('.vt').textContent = fmtDur(active && t > 0 ? t : dur);
    }
  },
};
function barsFor(id) {
  let x = (Number(String(id).replace(/\D/g, '')) || 7) * 9301 + 49297;
  return Array.from({ length: 28 }, () => { x = (x * 9301 + 49297) % 233280; return 25 + Math.round((x / 233280) * 75); });
}

// ---------- recorder ----------
const Rec = { mr: null, mic: null, chunks: [], started: 0, timer: null, cancelled: false };

class ChatPage extends Page {
  build() {
    this.replyTo = null;
    this.editing = null;
    this.attach = null;
    this.innerHTML = `
      <ion-header translucent>
        <ion-toolbar>
          <ion-buttons slot="start"><ion-back-button text="${MODE === 'ios' ? 'Чаты' : ''}"></ion-back-button></ion-buttons>
          <ion-title><div class="chat-title" id="title"></div></ion-title>
          <ion-buttons slot="end">
            <ion-button class="toolbar-icon" id="aCall" aria-label="Позвонить">${I.phone}</ion-button>
            <ion-button class="toolbar-icon" id="vCall" aria-label="Видеозвонок">${I.video}</ion-button>
          </ion-buttons>
        </ion-toolbar>
        <div class="call-strip hidden" id="strip"><span>🔊</span><span id="stripText">Идёт звонок</span><ion-button size="small" color="success" id="join">Войти</ion-button></div>
      </ion-header>
      <ion-content fullscreen id="content"><div class="msgs" id="msgs"></div></ion-content>
      <ion-footer translucent class="composer-footer">
        <div class="composer-wrap glass">
          <div class="bar hidden" id="replyBar"><div class="grow"><b id="rbTitle"></b><span class="sub" id="rbText"></span></div><button class="x" id="rbX">${I.x}</button></div>
          <div class="bar hidden" id="attachBar"><div class="thumb" id="abThumb"></div><div class="grow"><b id="abName"></b><span class="sub" id="abInfo"></span></div><button class="x" id="abX">${I.x}</button></div>
          <div class="bar hidden" id="recBar"><span class="rec-dot"></span><b id="recTime">0:00</b><span class="sub grow">Запись голосового…</span><button class="x" id="recX">${I.trash}</button><button class="c-btn send" id="recSend">${I.send}</button></div>
          <div class="composer" id="composer">
            <button class="c-btn" id="plus" aria-label="Прикрепить">${I.plus}</button>
            <textarea id="input" rows="1" placeholder="Сообщение"></textarea>
            <button class="c-btn" id="mic" aria-label="Голосовое">${I.mic}</button>
            <button class="c-btn send hidden" id="send" aria-label="Отправить">${I.send}</button>
          </div>
        </div>
      </ion-footer>`;
    this.content = $(this, '#content');
    this.input = $(this, '#input');
    this.wire();
  }
  get chat() { return S.chats.get(this.chatId); }

  attached() {
    S.active = this.chatId;
    this.renderHeader();
    this.load();
  }
  detached() {
    if (S.active === this.chatId) S.active = null;
    Voice.stop();
    if (Rec.mr) this.stopRec(true);
    C.emit('chats');
  }
  subscribe() {
    const mine = (id) => id === this.chatId;
    return [
      on('messages', (id) => { if (mine(id)) this.renderMessages(); }),
      on('read', (id) => { if (mine(id)) this.renderMessages(); }),
      on('typing', (id) => { if (mine(id)) this.renderHeader(); }),
      on('users', () => this.renderHeader()),
      on('chats', () => this.renderHeader()),
      on('callstate', (ev) => { if (mine(ev.chatId)) this.renderHeader(); }),
      on('progress', (p) => { if (mine(p.chatId)) { const bar = $(this, `[data-id="${p.id}"] .progress div`); if (bar) bar.style.width = `${Math.round(p.progress * 100)}%`; } }),
      on('resync', () => this.load(true)),
    ];
  }

  async load(force) {
    if (force || !S.messages.has(this.chatId)) {
      try { await C.loadMessages(this.chatId); } catch (err) { return toast(err.message); }
    }
    this.renderMessages(true);
    C.markRead(this.chatId);
  }

  renderHeader() {
    const chat = this.chat;
    if (!chat) return;
    const typing = C.typingNames(chat.id);
    let html;
    if (chat.type === 'group') {
      const online = chat.members.filter((m) => C.fresh(m).online).length;
      const sub = typing.length ? `${typing.join(', ')} печатает…` : `${chat.members.length} участн. · ${online} в сети`;
      html = `${groupAvatar(chat)}<div class="t"><span class="n">${esc(chat.title)}</span><span class="s ${typing.length ? 'typing' : ''}">${esc(sub)}</span></div>`;
    } else {
      const o = C.fresh(C.otherMember(chat));
      const sub = typing.length ? 'печатает…' : lastSeenText(o);
      html = `${avatarOf(o, { dot: true })}<div class="t"><span class="n">${esc(o.displayName)}${checkOf(o)}</span><span class="s ${typing.length ? 'typing' : ''}">${esc(sub)}</span></div>`;
    }
    $(this, '#title').innerHTML = html;
    const parts = chat.call?.participants || [];
    const showStrip = parts.length > 0 && !inCall(chat.id);
    $(this, '#strip').classList.toggle('hidden', !showStrip);
    if (showStrip) $(this, '#stripText').textContent = `Идёт звонок · ${parts.map(C.userName).join(', ')}`;
  }

  async renderMessages(scrollEnd) {
    const st = S.messages.get(this.chatId);
    const chat = this.chat;
    if (!st || !chat) return;
    const box = $(this, '#msgs');
    const scrollEl = await this.content.getScrollElement();
    const nearBottom = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < 160;
    const html = [];
    if (st.hasMore) html.push('<ion-button fill="clear" size="small" class="load-more" id="more">Показать ранее</ion-button>');
    else if (chat.type === 'direct') {
      const o = C.fresh(C.otherMember(chat));
      html.push(`<div class="msgs-top">${avatarOf(o)}<b>${esc(o.displayName)}</b>Начало переписки с @${esc(o.username)}</div>`);
    } else html.push(`<div class="msgs-top">${groupAvatar(chat)}<b>${esc(chat.title)}</b>Начало истории группы</div>`);
    const list = st.list;
    for (let i = 0; i < list.length; i++) {
      const m = list[i], prev = list[i - 1], next = list[i + 1];
      const newDay = !prev || dayKey(m.createdAt) !== dayKey(prev.createdAt);
      if (newDay) html.push(`<div class="day">${dayLabel(m.createdAt)}</div>`);
      const first = newDay || prev.userId !== m.userId || m.createdAt - prev.createdAt > 5 * 60e3 || !!m.replyTo;
      const last = !next || next.userId !== m.userId || next.createdAt - m.createdAt > 5 * 60e3 || dayKey(next.createdAt) !== dayKey(m.createdAt) || !!next.replyTo;
      html.push(this.messageHtml(m, chat, first, last));
    }
    box.innerHTML = html.join('');
    Voice.paint();
    if (scrollEnd || nearBottom) {
      this.content.scrollToBottom(scrollEnd ? 0 : 200);
      box.querySelectorAll('img').forEach((img) => { if (!img.complete) img.addEventListener('load', () => this.content.scrollToBottom(0), { once: true }); });
    }
  }

  messageHtml(m, chat, first, last) {
    const mine = m.userId === S.me.id;
    const fresh = m.freshAt && Date.now() - m.freshAt < 300;
    const u = { id: m.userId };
    const showAuthor = chat.type === 'group' && !mine && first;
    let att = '', media = false;
    if (m.image) {
      media = true;
      const { w, h } = m.image;
      const ratio = w && h ? ` style="aspect-ratio:${w}/${h}"` : '';
      att = `<img class="photo" src="${esc(m.localUrl || abs(m.image.url))}" data-full="${esc(abs(m.image.url))}"${ratio} alt="">`;
    } else if (m.file) {
      const f = m.file, url = m.localUrl || abs(f.url);
      if (f.kind === 'voice') {
        att = `<div class="voice" data-voice="${m.id}" data-src="${esc(url)}" data-dur="${f.duration || 0}">
          <button class="vp">${I.play}</button><div class="bars">${barsFor(m.id).map((x) => `<i style="height:${x}%"></i>`).join('')}</div><span class="vt">${fmtDur(f.duration)}</span></div>`;
      } else if (f.kind === 'video' && !m.pending) {
        media = true;
        att = `<video class="video" src="${esc(url)}" controls playsinline preload="metadata"></video>`;
      } else {
        att = `<div class="file" data-file="${esc(abs(f.url))}"><div class="ico">${I.file}</div><div style="min-width:0"><div class="fn">${esc(f.name)}</div><div class="fs">${fmtSize(f.size || 0)}</div></div></div>`;
      }
    }
    const reply = m.replyTo ? (m.replyTo.deleted
      ? '<div class="reply"><span>Сообщение удалено</span></div>'
      : `<div class="reply" data-jump="${m.replyTo.id}"><b>${esc(C.userName(m.replyTo.userId))}</b><span>${esc(m.replyTo.text || replyKindText(m.replyTo))}</span></div>`) : '';
    let ticks = '';
    if (mine) {
      const read = !m.pending && m.id <= (chat.othersRead || 0);
      ticks = `<span class="${read ? 'read' : ''}">${m.pending ? I.clock : read ? I.checks : I.check}</span>`;
    }
    const meta = `<span class="meta ${mine && !m.pending && m.id <= (chat.othersRead || 0) ? 'read' : ''}">${m.editedAt ? '<span class="edited">изм.</span>' : ''}${hhmm(m.createdAt)}${ticks}</span>`;
    const text = m.text ? `<div class="text">${linkify(m.text)}</div>` : '';
    const progress = m.pending && m.progress != null ? `<div class="progress"><div style="width:${Math.round(m.progress * 100)}%"></div></div>` : '';
    const bubbleCls = ['bubble', media && 'media', media && m.text && 'with-text'].filter(Boolean).join(' ');
    return `<div class="m ${mine ? 'mine' : ''} ${first ? 'first' : ''} ${last ? 'last' : ''} ${fresh ? 'fresh' : ''}" data-id="${m.id}">
      <div class="m-av">${!mine && last && chat.type === 'group' ? avatarOf(u) : ''}</div>
      <div class="${bubbleCls}">${showAuthor ? `<div class="author">${esc(C.userName(m.userId))}${checkOf(u)}</div>` : ''}${reply}${att}${text}${meta}${progress}</div>
    </div>`;
  }

  find(id) { return S.messages.get(this.chatId)?.list.find((m) => String(m.id) === String(id)); }

  wire() {
    const box = $(this, '#msgs');
    // --- taps ---
    box.addEventListener('click', async (e) => {
      if (this._longPressed) { this._longPressed = false; return; }
      const t = e.target;
      if (t.closest('#more')) {
        const st = S.messages.get(this.chatId);
        const before = st.list.find((m) => !m.pending)?.id;
        const el = await this.content.getScrollElement();
        const h = el.scrollHeight;
        await C.loadMessages(this.chatId, before);
        await this.renderMessages();
        el.scrollTop = el.scrollHeight - h;
        return;
      }
      const ext = t.closest('[data-ext]');
      if (ext) { e.preventDefault(); return Native.openExternal(ext.dataset.ext); }
      const photo = t.closest('img.photo');
      if (photo) return openLightbox(photo.dataset.full, photo.currentSrc || photo.src);
      const vp = t.closest('.vp');
      if (vp) return Voice.toggle(vp.closest('[data-voice]'));
      const bars = t.closest('.bars');
      if (bars) { const r = bars.getBoundingClientRect(); return Voice.seek(bars.closest('[data-voice]'), (e.clientX - r.left) / r.width); }
      const file = t.closest('[data-file]');
      if (file) return Native.openExternal(file.dataset.file);
      const jump = t.closest('[data-jump]');
      if (jump) {
        const el = $(this, `.m[data-id="${jump.dataset.jump}"]`);
        if (el) { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); Native.haptic(); }
        else toast('Сообщение выше в истории');
      }
    });
    // --- long press → actions ---
    let timer = null, startX = 0, startY = 0, target = null;
    const cancel = () => { clearTimeout(timer); timer = null; target?.classList.remove('pressed'); target = null; };
    box.addEventListener('pointerdown', (e) => {
      const bubble = e.target.closest('.bubble');
      if (!bubble || e.target.closest('video, .vp, .bars')) return;
      startX = e.clientX; startY = e.clientY; target = bubble;
      timer = setTimeout(() => {
        const id = bubble.closest('.m').dataset.id;
        this._longPressed = true;
        bubble.classList.add('pressed');
        Native.haptic('MEDIUM');
        this.actions(this.find(id)).finally(() => bubble.classList.remove('pressed'));
        timer = null;
      }, 420);
    });
    box.addEventListener('pointermove', (e) => { if (timer && Math.hypot(e.clientX - startX, e.clientY - startY) > 10) cancel(); });
    box.addEventListener('pointerup', () => { if (timer) cancel(); });
    box.addEventListener('pointercancel', cancel);
    box.addEventListener('contextmenu', (e) => { if (e.target.closest('.bubble')) e.preventDefault(); });

    // --- header ---
    $(this, '#title').addEventListener('click', () => {
      const chat = this.chat;
      if (chat.type === 'group') push('ethic-group', { chatId: chat.id });
      else push('ethic-user', { userId: C.otherMember(chat).id });
    });
    $(this, '#aCall').addEventListener('click', () => startCall(this.chatId, false));
    $(this, '#vCall').addEventListener('click', () => startCall(this.chatId, true));
    $(this, '#join').addEventListener('click', () => joinCall(this.chatId));

    // --- composer ---
    const input = this.input;
    const autosize = () => { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 140) + 'px'; };
    this.autosize = autosize;
    let lastTyping = 0;
    input.addEventListener('input', () => {
      autosize();
      this.buttons();
      if (Date.now() - lastTyping > 2500 && input.value.trim() && !this.editing) { lastTyping = Date.now(); C.wsSend({ type: 'typing', chatId: this.chatId }); }
    });
    input.addEventListener('focus', () => setTimeout(() => this.content.scrollToBottom(200), 300));
    $(this, '#send').addEventListener('click', () => this.send());
    $(this, '#plus').addEventListener('click', () => this.pickAttachment());
    $(this, '#mic').addEventListener('click', () => this.startRec());
    $(this, '#recX').addEventListener('click', () => this.stopRec(true));
    $(this, '#recSend').addEventListener('click', () => this.stopRec(false));
    $(this, '#rbX').addEventListener('click', () => { this.editing ? this.cancelEdit() : this.setReply(null); });
    $(this, '#abX').addEventListener('click', () => this.setAttach(null));
  }

  buttons() {
    const has = !!this.input.value.trim() || !!this.attach || !!this.editing;
    $(this, '#send').classList.toggle('hidden', !has);
    $(this, '#mic').classList.toggle('hidden', has);
  }

  async actions(m) {
    if (!m || m.pending) return;
    const mine = m.userId === S.me.id;
    const act = await sheet(null, [
      { id: 'reply', text: 'Ответить' },
      ...(m.text ? [{ id: 'copy', text: 'Копировать' }] : []),
      ...(mine && m.file?.kind !== 'voice' ? [{ id: 'edit', text: 'Изменить' }] : []),
      ...(mine || S.me.dev ? [{ id: 'delete', text: 'Удалить', destructive: true }] : []),
    ]);
    if (act === 'reply') this.setReply(m);
    if (act === 'copy') { await navigator.clipboard?.writeText(m.text).catch(() => {}); toast('Скопировано'); }
    if (act === 'edit') this.startEdit(m);
    if (act === 'delete' && await confirmDialog('Удалить сообщение?', 'Оно пропадёт у всех участников.', 'Удалить', true)) {
      C.wsSend({ type: 'delete', chatId: this.chatId, messageId: m.id });
    }
  }
  setReply(m) {
    this.replyTo = m;
    $(this, '#replyBar').classList.toggle('hidden', !m);
    if (m) {
      $(this, '#rbTitle').textContent = `Ответ ${C.userName(m.userId)}`;
      $(this, '#rbText').textContent = m.text || replyKindText({ kind: m.image ? 'photo' : m.file?.kind, fileName: m.file?.name });
      this.input.focus();
    }
  }
  startEdit(m) {
    this.setReply(null);
    this.editing = m;
    this.input.value = m.text;
    this.autosize();
    $(this, '#replyBar').classList.remove('hidden');
    $(this, '#rbTitle').textContent = 'Редактирование';
    $(this, '#rbText').textContent = m.text;
    this.buttons();
    this.input.focus();
  }
  cancelEdit() {
    this.editing = null;
    this.input.value = '';
    this.autosize();
    $(this, '#replyBar').classList.add('hidden');
    this.buttons();
  }

  async pickAttachment() {
    const act = await sheet(null, [{ id: 'media', text: 'Фото или видео' }, { id: 'camera', text: 'Камера' }, { id: 'file', text: 'Файл' }]);
    if (!act) return;
    const file = await pickFile(act === 'media' ? 'image/*,video/*' : act === 'camera' ? 'image/*' : '', act === 'camera' ? 'environment' : '');
    if (file) this.setAttach(file);
  }
  async setAttach(file) {
    if (this.attach?.url) URL.revokeObjectURL(this.attach.url);
    this.attach = null;
    $(this, '#attachBar').classList.toggle('hidden', !file);
    if (!file) return this.buttons();
    try {
      if (file.size > 50 * 1024 * 1024) throw new Error('Файл больше 50 МБ');
      if (C.isImageFile(file) && !/heic|heif/i.test(file.type + file.name)) {
        const img = await C.prepareImage(file);
        this.attach = { mode: 'image', ...img, name: file.name || 'фото', size: img.blob.size };
        $(this, '#abThumb').innerHTML = `<img class="thumb" src="${img.url}" alt="">`;
        $(this, '#abInfo').textContent = `Фото · ${fmtSize(img.blob.size)}`;
      } else {
        this.attach = { mode: 'file', blob: file, name: file.name || 'файл', size: file.size, mime: file.type, url: URL.createObjectURL(file) };
        $(this, '#abThumb').innerHTML = I.file;
        $(this, '#abInfo').textContent = fmtSize(file.size);
      }
      $(this, '#abName').textContent = this.attach.name;
    } catch (err) { toast(err.message); $(this, '#attachBar').classList.add('hidden'); }
    this.buttons();
  }

  send() {
    const text = this.input.value.trim();
    const chatId = this.chatId;
    if (S.ws?.readyState !== 1) return toast('Нет соединения, переподключаемся…');
    if (this.editing) {
      const m = this.editing;
      if (text !== m.text && (text || m.image || m.file)) {
        C.wsSend({ type: 'edit', chatId, messageId: m.id, text });
        m.text = text; m.editedAt = Date.now();
      }
      return this.cancelEdit();
    }
    const attach = this.attach;
    if (!text && !attach) return;
    const replyTo = this.replyTo;
    this.input.value = '';
    this.autosize();
    this.attach = null;
    $(this, '#attachBar').classList.add('hidden');
    this.setReply(null);
    this.buttons();
    Native.haptic();
    if (!attach) return C.sendText(chatId, text, replyTo);
    if (attach.mode === 'image') {
      return C.sendUpload(chatId, attach.blob, {
        kind: 'image', name: attach.name, text, replyTo,
        pendingExtra: { image: { url: attach.url, w: attach.w, h: attach.h }, localUrl: attach.url },
        build: (r) => ({ image: { url: r.url, w: attach.w, h: attach.h } }),
      });
    }
    C.sendUpload(chatId, attach.blob, {
      kind: 'file', name: attach.name, text, replyTo,
      pendingExtra: { file: { url: attach.url, name: attach.name, size: attach.size, kind: 'file' }, localUrl: attach.url },
      build: (r) => {
        const media = ['mp4', 'webm', 'ogg', 'mp3', 'wav'].includes(r.ext);
        const kind = media && /^video\//.test(attach.mime) ? 'video' : media && /^audio\//.test(attach.mime) ? 'audio' : 'file';
        return { file: { url: r.url, name: attach.name, size: r.size, kind } };
      },
    });
  }

  async startRec() {
    if (Rec.mr) return;
    if (!window.MediaRecorder) return toast('Запись голоса не поддерживается');
    try { Rec.mic = await C.NS.micStream(); } catch (e) { return toast(C.mediaError(e, 'микрофону')); }
    const mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus', 'audio/webm'].find((t) => MediaRecorder.isTypeSupported(t));
    Rec.mr = new MediaRecorder(Rec.mic.stream, mime ? { mimeType: mime, audioBitsPerSecond: 128000 } : { audioBitsPerSecond: 128000 });
    Rec.chunks = [];
    Rec.cancelled = false;
    Rec.mr.ondataavailable = (e) => { if (e.data.size) Rec.chunks.push(e.data); };
    Rec.mr.onstop = () => {
      const dur = (Date.now() - Rec.started) / 1000;
      Rec.mic.stop();
      clearInterval(Rec.timer);
      $(this, '#recBar').classList.add('hidden');
      $(this, '#composer').classList.remove('hidden');
      const blob = new Blob(Rec.chunks, { type: Rec.mr.mimeType || 'audio/webm' });
      Rec.mr = null;
      if (Rec.cancelled) return;
      if (dur < 0.6 || !blob.size) return toast('Слишком короткое сообщение');
      const url = URL.createObjectURL(blob);
      const ext = blob.type.includes('mp4') ? 'm4a' : blob.type.includes('ogg') ? 'ogg' : 'webm';
      C.sendUpload(this.chatId, blob, {
        kind: 'voice', name: `voice.${ext}`, replyTo: this.replyTo,
        pendingExtra: { file: { url, name: 'Голосовое сообщение', size: blob.size, kind: 'voice', duration: dur }, localUrl: url },
        build: (r) => ({ file: { url: r.url, name: 'Голосовое сообщение', size: r.size, kind: 'voice', duration: dur } }),
      });
      this.setReply(null);
    };
    Rec.mr.start(250);
    Rec.started = Date.now();
    Native.haptic('MEDIUM');
    $(this, '#recTime').textContent = '0:00';
    $(this, '#recBar').classList.remove('hidden');
    $(this, '#composer').classList.add('hidden');
    Rec.timer = setInterval(() => {
      const s = (Date.now() - Rec.started) / 1000;
      $(this, '#recTime').textContent = fmtDur(s);
      if (s >= 300) this.stopRec(false);
    }, 250);
  }
  stopRec(cancel) {
    if (!Rec.mr || Rec.mr.state === 'inactive') return;
    Rec.cancelled = cancel;
    Rec.mr.stop();
  }
}

class LightboxPage extends Page {
  build() {
    this.innerHTML = `
      <ion-header translucent><ion-toolbar>
        <ion-buttons slot="end"><ion-button id="close">Закрыть</ion-button></ion-buttons>
      </ion-toolbar></ion-header>
      <ion-content class="lightbox" fullscreen><div class="lb"><img src="${esc(this.src)}" alt=""></div></ion-content>`;
    $(this, '#close').addEventListener('click', () => this.closest('ion-modal').dismiss());
  }
}
function openLightbox(full, shown) {
  const el = document.createElement('ethic-lightbox');
  el.src = shown || full;
  presentModal(el, { card: false });
}

customElements.define('ethic-chat', ChatPage);
customElements.define('ethic-lightbox', LightboxPage);
