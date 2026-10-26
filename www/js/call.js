// Calls: LiveKit room, full-screen call UI, incoming call screen, "add to call".
import * as C from './core.js';
import { $, esc, I, avatarOf, groupAvatar, checkOf, toast, presentModal } from './ui.js';
import { Page } from './page.js';
import { openPicker } from './screens.js';

const { S, on, api, Native, Sound, NS, CallNative } = C;
const LK = window.LivekitClient;

let call = null; // { chatId, room, title, startedAt, timer, tiles, mic, facing, modal, el }
export const inCall = (chatId) => !!call && call.chatId === chatId;
const pill = () => document.getElementById('callPill');

function callTitle(chatId, fallback) {
  const chat = S.chats.get(chatId);
  if (!chat) return esc(fallback || 'Звонок');
  return chat.type === 'group' ? esc(chat.title) : `${esc(C.chatName(chat))}${checkOf(C.otherMember(chat))}`;
}

export async function startCall(chatId, video, fallbackTitle) {
  if (call) {
    if (call.chatId === chatId) return showCall();
    return toast('Сначала завершите текущий звонок');
  }
  let creds;
  try { creds = await api(`/api/chats/${chatId}/call`, { method: 'POST', body: { video } }); }
  catch (err) { return toast(err.message); }
  Sound.unlock();
  const room = new LK.Room({ adaptiveStream: false, dynacast: true, audioCaptureDefaults: NS.constraints() });
  call = { chatId, room, title: callTitle(chatId, fallbackTitle), startedAt: null, timer: null, tiles: new Map(), mic: null, facing: 'user' };
  await showCall();

  const E = LK.RoomEvent;
  const rerender = () => { renderTiles(); renderControls(); };
  room
    .on(E.ParticipantConnected, () => { Sound.stop(); Sound.join(); Native.haptic(); rerender(); })
    .on(E.ParticipantDisconnected, () => { Sound.leave(); rerender(); })
    .on(E.TrackSubscribed, (track) => { if (track.kind === 'audio') document.getElementById('audioSink').append(track.attach()); rerender(); })
    .on(E.TrackUnsubscribed, (track) => { if (track.kind === 'audio') track.detach().forEach((el) => el.remove()); rerender(); })
    .on(E.TrackMuted, rerender).on(E.TrackUnmuted, rerender)
    .on(E.LocalTrackPublished, rerender).on(E.LocalTrackUnpublished, rerender)
    .on(E.ActiveSpeakersChanged, renderTiles)
    .on(E.Reconnecting, () => setStatus('Переподключение…'))
    .on(E.Reconnected, tick)
    .on(E.Disconnected, () => { if (call?.room === room) leaveCall(true); });

  // Android: switch to call audio before any call sound plays (call volume, not media).
  if (CallNative.on) { const r = await CallNative.call('callStart', { speaker: true }); call.speaker = r?.speaker ?? true; }
  try { await room.connect(creds.url, creds.token); }
  catch (err) { console.error(err); toast('Не удалось подключиться к звонку'); return leaveCall(true); }
  C.wsSend({ type: 'call:join', chatId });
  call.startedAt = Date.now();
  call.timer = setInterval(tick, 1000);
  tick();
  room.startAudio().catch(() => {});
  Sound.join();
  if (room.remoteParticipants.size === 0) {
    setTimeout(() => { if (call?.room === room && room.remoteParticipants.size === 0) Sound.ringback(); }, 600);
    setTimeout(() => { if (call?.room === room && room.remoteParticipants.size === 0) Sound.stop(); }, 60_000);
  }
  await publishMic(room);
  // Android: foreground service keeps the mic working when the app is in the background.
  if (CallNative.on && call?.room === room) {
    const t = document.createElement('div'); t.innerHTML = call.title;
    await CallNative.call('callService', { title: t.textContent });
    if (call?.room !== room) CallNative.call('callStop');
  }
  if (video) { try { await room.localParticipant.setCameraEnabled(true, { facingMode: 'user' }); } catch (e) { toast(C.mediaError(e, 'камере')); } }
  rerender();
}
export const joinCall = (chatId) => startCall(chatId, false);

async function publishMic(room) {
  const lp = room.localParticipant;
  if (NS.mode() !== 'strong') {
    try { await lp.setMicrophoneEnabled(true, NS.constraints()); } catch (e) { toast(C.mediaError(e, 'микрофону')); }
    return;
  }
  let mic;
  try { mic = await NS.micStream(); } catch (e) { return toast(C.mediaError(e, 'микрофону')); }
  try {
    const track = new LK.LocalAudioTrack(mic.stream.getAudioTracks()[0], undefined, true);
    await lp.publishTrack(track, { source: LK.Track.Source.Microphone, dtx: true, red: true });
    if (call?.room === room) call.mic = mic; else mic.stop();
  } catch (e) {
    console.error(e);
    mic.stop();
    await lp.setMicrophoneEnabled(true, NS.constraints()).catch(() => {});
  }
}

export function leaveCall(fromDisconnect = false) {
  if (!call) return;
  const c = call;
  call = null;
  Sound.stop();
  Sound.leave();
  CallNative.call('callStop');
  clearInterval(c.timer);
  C.wsSend({ type: 'call:leave', chatId: c.chatId });
  if (!fromDisconnect) c.room.disconnect();
  c.mic?.stop();
  for (const el of c.tiles.values()) el._track?.detach();
  document.getElementById('audioSink').innerHTML = '';
  pill().classList.add('hidden');
  c.modal?.dismiss();
  C.emit('callstate', { chatId: c.chatId });
}

async function showCall() {
  if (!call) return;
  pill().classList.add('hidden');
  if (call.modal) return;
  const el = document.createElement('ethic-call');
  call.el = el;
  call.modal = await presentModal(el, {
    cls: 'call-modal', card: false,
    // Swiping the call away minimizes it; the call keeps going.
    onDismiss: () => { if (!call) return; call.modal = null; call.el = null; pill().classList.remove('hidden'); },
  });
  renderTiles();
  renderControls();
  tick();
}

function setStatus(text) { const s = call?.el && $(call.el, '#st'); if (s) s.textContent = text; }
function tick() {
  if (!call?.startedAt) return setStatus('Соединение…');
  const s = Math.floor((Date.now() - call.startedAt) / 1000);
  const n = call.room.remoteParticipants.size;
  const t = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  setStatus(n ? `${t} · ${n + 1} участн.` : `${t} · ожидание…`);
  pill().textContent = `Вернуться к звонку · ${t}`;
}

function renderControls() {
  if (!call?.el) return;
  const lp = call.room.localParticipant;
  const mic = lp.isMicrophoneEnabled, cam = lp.isCameraEnabled;
  const b = (id) => $(call.el, `#${id}`);
  b('mic').innerHTML = mic ? I.mic : I.micOff;
  b('mic').className = `round ${mic ? '' : 'off'}`;
  b('cam').innerHTML = cam ? I.video : I.videoOff;
  b('cam').className = `round ${cam ? '' : 'off'}`;
  b('flip').classList.toggle('hidden', !cam);
  b('spk').classList.toggle('hidden', !CallNative.on);
  b('spk').innerHTML = call.speaker ? I.speaker : I.earpiece;
  b('spk').className = `round ${CallNative.on ? '' : 'hidden'} ${call.speaker ? 'on' : ''}`;
}

function renderTiles() {
  if (!call?.el) return;
  const grid = $(call.el, '#grid');
  const { room, tiles } = call;
  const Src = LK.Track.Source;
  const people = [room.localParticipant, ...room.remoteParticipants.values()];
  const wanted = new Map();
  for (const p of people) {
    const scr = p.getTrackPublication(Src.ScreenShare);
    if (scr?.track && !scr.isMuted) wanted.set(`${p.identity}:screen`, { p, track: scr.track, screen: true });
    const cam = p.getTrackPublication(Src.Camera);
    wanted.set(`${p.identity}:cam`, { p, track: cam?.track && !cam.isMuted ? cam.track : null, screen: false });
  }
  for (const [key, el] of tiles) if (!wanted.has(key) || !grid.contains(el)) { el._track?.detach(); el.remove(); tiles.delete(key); }
  const speaking = new Set(room.activeSpeakers.map((p) => p.identity));
  for (const [key, w] of wanted) {
    let el = tiles.get(key);
    const local = w.p === room.localParticipant;
    const uid = Number(w.p.identity) || 0;
    const name = local ? 'Вы' : (w.p.name || C.userName(uid));
    if (!el) {
      el = document.createElement('div');
      el.className = 'tile';
      el.innerHTML = `<video autoplay playsinline muted></video>${avatarOf({ id: uid, displayName: w.p.name || name })}<div class="lbl"></div>`;
      tiles.set(key, el);
      if (w.screen) grid.prepend(el); else grid.append(el);
    }
    el.classList.toggle('screen', w.screen);
    el.classList.toggle('mirror', local && !w.screen && call.facing === 'user');
    el.classList.toggle('speaking', !w.screen && speaking.has(w.p.identity));
    el.classList.toggle('has-video', !!w.track);
    const video = el.querySelector('video');
    if (w.track !== el._track) { el._track?.detach(video); if (w.track) w.track.attach(video); el._track = w.track; }
    const micOff = !w.screen && !w.p.isMicrophoneEnabled;
    el.querySelector('.lbl').innerHTML = `${micOff ? I.micOff : ''}${esc(w.screen ? `Экран: ${name}` : name)}`;
  }
  grid.dataset.n = String(Math.min(tiles.size, 4));
  tick();
}

class CallPage extends Page {
  build() {
    this.innerHTML = `
      <div class="call-page">
        <div class="call-top">
          <button class="round" id="min" style="width:40px;height:40px" aria-label="Свернуть">${I.minimize}</button>
          <div class="grow"><div class="who">${call?.title || 'Звонок'}</div><div class="st" id="st">Соединение…</div></div>
          <button class="round" id="add" style="width:40px;height:40px" aria-label="Добавить в звонок">${I.userPlus}</button>
        </div>
        <div class="grid" id="grid"></div>
        <div class="controls">
          <button class="round hidden" id="spk" aria-label="Динамик"></button>
          <button class="round" id="mic" aria-label="Микрофон"></button>
          <button class="round" id="cam" aria-label="Камера"></button>
          <button class="round hidden" id="flip" aria-label="Перевернуть камеру">${I.flip}</button>
          <button class="round red" id="leave" aria-label="Завершить">${I.hangup}</button>
        </div>
      </div>`;
    $(this, '#min').addEventListener('click', () => call?.modal?.dismiss());
    $(this, '#leave').addEventListener('click', () => leaveCall());
    $(this, '#spk').addEventListener('click', async () => {
      const r = await CallNative.call('setSpeaker', { on: !call.speaker });
      if (call) call.speaker = r?.speaker ?? !call.speaker;
      Native.haptic(); renderControls();
    });
    $(this, '#mic').addEventListener('click', async () => {
      const lp = call.room.localParticipant;
      try { await lp.setMicrophoneEnabled(!lp.isMicrophoneEnabled); } catch (e) { toast(C.mediaError(e, 'микрофону')); }
      Native.haptic(); renderControls(); renderTiles();
    });
    $(this, '#cam').addEventListener('click', async () => {
      const lp = call.room.localParticipant;
      try { await lp.setCameraEnabled(!lp.isCameraEnabled, { facingMode: call.facing }); } catch (e) { toast(C.mediaError(e, 'камере')); }
      Native.haptic(); renderControls(); renderTiles();
    });
    $(this, '#flip').addEventListener('click', async () => {
      const track = call.room.localParticipant.getTrackPublication(LK.Track.Source.Camera)?.track;
      if (!track) return;
      const facing = call.facing === 'user' ? 'environment' : 'user';
      try { await track.restartTrack({ facingMode: facing }); call.facing = facing; } catch { toast('Не удалось переключить камеру'); }
      Native.haptic(); renderTiles();
    });
    $(this, '#add').addEventListener('click', () => {
      const inRoom = [S.me.id, ...[...call.room.remoteParticipants.values()].map((p) => Number(p.identity))];
      openPicker({
        title: 'Добавить в звонок', doneText: 'Позвать', exclude: inRoom,
        onPick: async (uid) => {
          await api(`/api/chats/${call.chatId}/call/invite`, { method: 'POST', body: { userId: uid } });
          toast(`Звоним: ${C.userName(uid)}…`);
        },
      });
    });
  }
}

// ---------- incoming ----------
let incoming = null;
function onRing(ev) {
  if (call || incoming) return;
  C.rememberUsers([ev.from]);
  const chat = S.chats.get(ev.chatId);
  const el = document.createElement('ethic-incoming');
  el.ev = ev;
  el.who = chat?.type === 'group' ? esc(chat.title) : ev.invite && ev.title ? esc(ev.title) : `${esc(ev.from.displayName)}${checkOf(ev.from)}`;
  el.avatar = chat?.type === 'group' ? groupAvatar(chat) : avatarOf(ev.from);
  el.kind = ev.invite ? `${ev.from.displayName} зовёт вас в звонок` : `${chat?.type === 'group' ? `${ev.from.displayName} · ` : ''}${ev.video ? 'Видеозвонок' : 'Аудиозвонок'} Ethic`;
  incoming = { ev, timer: setTimeout(() => hideIncoming(), 45_000), vib: null };
  // On Android the system rings and vibrates itself (ring volume, silent mode).
  if (!Sound.ring() && navigator.vibrate) { navigator.vibrate([600, 400]); incoming.vib = setInterval(() => navigator.vibrate([600, 400]), 1000); }
  presentModal(el, { card: false, cls: 'call-modal' }).then((m) => { if (incoming) incoming.modal = m; else m.dismiss(); });
}
function hideIncoming() {
  if (!incoming) return;
  clearTimeout(incoming.timer);
  clearInterval(incoming.vib);
  navigator.vibrate?.(0);
  Sound.stop();
  incoming.modal?.dismiss();
  incoming = null;
}
class IncomingPage extends Page {
  build() {
    this.innerHTML = `
      <div class="incoming">
        <div>${this.avatar}<div class="who">${this.who}</div><div class="kind">${esc(this.kind)}</div></div>
        <div class="acts">
          <div><button class="round red" id="no" aria-label="Отклонить">${I.hangup}</button>Отклонить</div>
          <div><button class="round green" id="yes" aria-label="Ответить">${I.phone}</button>Ответить</div>
          ${this.ev.video ? `<div><button class="round green" id="yesv" aria-label="С видео">${I.video}</button>С видео</div>` : ''}
        </div>
      </div>`;
    const answer = (video) => {
      const ev = this.ev;
      hideIncoming();
      setTimeout(() => startCall(ev.chatId, video, ev.title || ev.from?.displayName), 350);
    };
    $(this, '#no').addEventListener('click', () => { C.wsSend({ type: 'call:decline', chatId: this.ev.chatId }); hideIncoming(); });
    $(this, '#yes').addEventListener('click', () => answer(false));
    $(this, '#yesv')?.addEventListener('click', () => answer(true));
  }
}

on('ring', onRing);
on('callstate', (ev) => { if (incoming && incoming.ev.chatId === ev.chatId && !ev.call) hideIncoming(); });
on('declined', (ev) => { if (call && call.chatId === ev.chatId) { toast(`${C.userName(ev.userId)} отклоняет звонок`); Sound.stop(); } });
on('invited', (ev) => { if (call && call.chatId === ev.chatId && ev.by !== S.me.id) toast(`${C.userName(ev.by)} зовёт: ${ev.user?.displayName || C.userName(ev.userId)}`); });
document.getElementById('callPill').addEventListener('click', showCall);
CallNative.onHangup(() => leaveCall());

customElements.define('ethic-call', CallPage);
customElements.define('ethic-incoming', IncomingPage);
