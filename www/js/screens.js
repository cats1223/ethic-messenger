// Screens: login, tabs (chats / people / settings), profiles, groups, profile editor.
import * as C from './core.js';
import {
  MODE, $, $$, esc, I, avatarOf, groupAvatar, checkOf, badgeOf, bannerStyle, listTime, lastSeenText, previewText,
  toast, sheet, confirmDialog, presentModal, pickFile,
} from './ui.js';
import { Page, nav, push } from './page.js';

const { S, on, api, store, Native, Sound } = C;
export const openChat = (chatId) => push('ethic-chat', { chatId });
const selectTab = (tab) => document.querySelector('ion-tabs')?.select(tab);

// ---------- login ----------
class LoginPage extends Page {
  build() {
    this.mode = 'login';
    this.innerHTML = `
      <ion-content fullscreen>
        <div class="login-wrap">
          <img class="login-logo" src="img/logo-full.png" alt="Ethic Messenger">
          <ion-segment value="login" id="seg">
            <ion-segment-button value="login"><ion-label>Вход</ion-label></ion-segment-button>
            <ion-segment-button value="register"><ion-label>Регистрация</ion-label></ion-segment-button>
          </ion-segment>
          <ion-list inset>
            <ion-item><ion-input id="u" label="Логин" label-placement="floating" autocapitalize="off" autocorrect="off" spellcheck="false"></ion-input></ion-item>
            <ion-item class="reg"><ion-input id="n" label="Отображаемое имя" label-placement="floating" maxlength="48"></ion-input></ion-item>
            <ion-item><ion-input id="p" type="password" label="Пароль" label-placement="floating"></ion-input></ion-item>
            <ion-item class="reg" id="inviteItem"><ion-input id="i" label="Код приглашения" label-placement="floating" autocapitalize="off"></ion-input></ion-item>
          </ion-list>
          <div class="login-err" id="err"></div>
          <ion-button expand="block" class="big-btn" id="go">Войти</ion-button>
        </div>
      </ion-content>`;
    const wrap = $(this, '.login-wrap');
    $(this, '#seg').addEventListener('ionChange', (e) => {
      this.mode = e.detail.value;
      wrap.classList.toggle('register', this.mode === 'register');
      $(this, '#go').textContent = this.mode === 'register' ? 'Создать аккаунт' : 'Войти';
      $(this, '#err').textContent = '';
    });
    api('/api/config').then((c) => { if (!c.inviteRequired) $(this, '#inviteItem').remove(); }).catch(() => {});
    $(this, '#go').addEventListener('click', () => this.submit());
    this.addEventListener('keydown', (e) => { if (e.key === 'Enter') this.submit(); });
  }
  async submit() {
    const v = (id) => $(this, id)?.value?.trim?.() || '';
    const fields = { username: v('#u'), password: $(this, '#p').value || '' };
    if (this.mode === 'register') { fields.displayName = v('#n'); fields.invite = v('#i'); }
    const btn = $(this, '#go');
    btn.disabled = true;
    $(this, '#err').textContent = '';
    try {
      Sound.unlock();
      await C.login(this.mode, fields);
      Native.haptic('MEDIUM');
      nav().setRoot('ethic-tabs', null, { animated: true, direction: 'forward' });
    } catch (err) {
      $(this, '#err').textContent = err.message;
    } finally { btn.disabled = false; }
  }
}

// ---------- tabs ----------
class TabsPage extends Page {
  build() {
    const tab = (id, icon, label, badge = '') => `
      <ion-tab-button tab="${id}">
        <span class="tab-icon">${icon}</span><ion-label>${label}</ion-label>${badge}
      </ion-tab-button>`;
    this.innerHTML = `
      <ion-tabs>
        <ion-tab tab="chats"><ethic-chats class="tab-page"></ethic-chats></ion-tab>
        <ion-tab tab="people"><ethic-people class="tab-page"></ethic-people></ion-tab>
        <ion-tab tab="settings"><ethic-settings class="tab-page"></ethic-settings></ion-tab>
        <ion-tab-bar slot="bottom" translucent>
          ${tab('chats', I.chats, 'Чаты', '<span class="tab-badge hidden" id="tb"></span>')}
          ${tab('people', I.people, 'Люди')}
          ${tab('settings', I.gear, 'Настройки')}
        </ion-tab-bar>
      </ion-tabs>`;
    const tabs = $(this, 'ion-tabs');
    tabs.componentOnReady?.().then(() => tabs.select('chats'));
    this.badge();
  }
  subscribe() { return [on('chats', () => this.badge())]; }
  badge() {
    const n = C.totalUnread();
    const b = $(this, '#tb');
    if (!b) return;
    b.textContent = n > 99 ? '99+' : n;
    b.classList.toggle('hidden', !n);
  }
}

// Large collapsing title on iOS; a plain toolbar + search row on Android.
function tabHeader(title, startBtns = '', endBtns = '', search = '') {
  const sb = search ? `<ion-searchbar id="sb" placeholder="${search}" debounce="200"></ion-searchbar>` : '';
  return MODE === 'ios'
    ? `<ion-header translucent><ion-toolbar>
         <ion-buttons slot="start">${startBtns}</ion-buttons><ion-title>${title}</ion-title><ion-buttons slot="end">${endBtns}</ion-buttons>
       </ion-toolbar></ion-header>
       <ion-content fullscreen>
         <ion-header collapse="condense"><ion-toolbar><ion-title size="large">${title}</ion-title></ion-toolbar>${sb ? `<ion-toolbar>${sb}</ion-toolbar>` : ''}</ion-header>`
    : `<ion-header><ion-toolbar><ion-title>${title}</ion-title>
         <ion-buttons slot="end">${startBtns}${endBtns}</ion-buttons></ion-toolbar>${sb ? `<ion-toolbar>${sb}</ion-toolbar>` : ''}</ion-header>
       <ion-content>`;
}

// ---------- chats ----------
class ChatsTab extends Page {
  build() {
    this.filter = '';
    this.innerHTML = `${tabHeader('Чаты',
      `<ion-button class="toolbar-icon" id="newGroup" aria-label="Новая группа">${I.group}</ion-button>`,
      `<ion-button class="toolbar-icon" id="compose" aria-label="Новый чат">${I.compose}</ion-button>`, 'Поиск')}
        <ion-refresher slot="fixed" id="ref"><ion-refresher-content></ion-refresher-content></ion-refresher>
        <ion-list id="list" lines="full"></ion-list>
      </ion-content>`;
    $(this, '#sb').addEventListener('ionInput', (e) => { this.filter = (e.detail.value || '').toLowerCase(); this.render(); });
    $(this, '#ref').addEventListener('ionRefresh', async (e) => { try { await C.loadChats(); } catch {} e.target.complete(); });
    $(this, '#newGroup').addEventListener('click', () => openGroupNew());
    $(this, '#compose').addEventListener('click', () => selectTab('people'));
    $(this, '#list').addEventListener('click', async (e) => {
      const leave = e.target.closest('[data-leave]');
      if (leave) {
        e.stopPropagation();
        const chat = S.chats.get(Number(leave.dataset.leave));
        await leave.closest('ion-item-sliding')?.close();
        if (chat) leaveChat(chat);
        return;
      }
      const item = e.target.closest('[data-chat]');
      if (item) openChat(Number(item.dataset.chat));
    });
    this.render();
  }
  subscribe() { return [on('chats', () => this.render()), on('users', () => this.render()), on('typing', () => this.render())]; }
  render() {
    const list = $(this, '#list');
    let chats = C.sortedChats();
    if (this.filter) chats = chats.filter((c) => C.chatName(c).toLowerCase().includes(this.filter));
    if (!chats.length) {
      list.innerHTML = `<div class="empty"><img class="art" src="img/logo.png" alt="">${this.filter ? 'Ничего не найдено' : 'Пока нет чатов.<br>Найдите друга во вкладке «Люди».'}</div>`;
      return;
    }
    list.innerHTML = chats.map((c) => {
      const group = c.type === 'group';
      const o = group ? null : C.fresh(C.otherMember(c));
      const n = c.unread || 0;
      const typing = C.typingNames(c.id);
      const lm = c.lastMessage;
      let sub = previewText(lm);
      if (lm && group) sub = `${lm.userId === S.me.id ? 'Вы' : C.userName(lm.userId)}: ${sub}`;
      else if (lm && lm.userId === S.me.id) sub = `Вы: ${sub}`;
      if (!lm) sub = group ? `${c.members.length} участн.` : lastSeenText(o);
      const sliding = `<ion-item-options side="end"><ion-item-option color="danger" data-leave="${c.id}">${group ? 'Выйти' : 'Удалить'}</ion-item-option></ion-item-options>`;
      return `<ion-item-sliding>
        <ion-item button detail="false" class="chat-item" data-chat="${c.id}">
          <div slot="start">${group ? groupAvatar(c) : avatarOf(o, { dot: true })}</div>
          <ion-label>
            <h2><span>${esc(group ? c.title : o?.displayName)}</span>${group ? '' : checkOf(o)}</h2>
            <p class="${typing.length ? 'typing' : ''}">${esc(typing.length ? 'печатает…' : c.call?.participants?.length ? '🔊 Идёт звонок' : sub)}</p>
          </ion-label>
          <div class="chat-meta" slot="end">
            <span class="chat-time ${n ? 'unread' : ''}">${listTime(lm?.createdAt)}</span>
            ${n ? `<span class="chat-badge">${n > 99 ? '99+' : n}</span>` : c.call?.participants?.length ? '<span class="in-call">● звонок</span>' : ''}
          </div>
        </ion-item>${sliding}</ion-item-sliding>`;
    }).join('');
  }
}

export async function leaveChat(chat) {
  const group = chat.type === 'group';
  const ok = await confirmDialog(group ? 'Покинуть группу?' : 'Удалить чат?', `«${C.chatName(chat)}» пропадёт из вашего списка вместе с историей.`, group ? 'Выйти' : 'Удалить', true);
  if (!ok) return false;
  try {
    await api(`/api/chats/${chat.id}/members/me`, { method: 'DELETE' });
    S.chats.delete(chat.id);
    S.messages.delete(chat.id);
    C.emit('chats');
    return true;
  } catch (err) { toast(err.message); return false; }
}

// ---------- people ----------
class PeopleTab extends Page {
  build() {
    this.innerHTML = `${tabHeader('Люди', '', '', 'Имя или логин')}
        <ion-list id="list" lines="full"></ion-list>
      </ion-content>`;
    $(this, '#sb').addEventListener('ionInput', (e) => this.load(e.detail.value || ''));
    $(this, '#list').addEventListener('click', async (e) => {
      const call = e.target.closest('[data-call]');
      const item = e.target.closest('[data-user]');
      if (!item) return;
      try {
        const chat = await C.openDirect(Number(item.dataset.user));
        if (call) { e.stopPropagation(); const { startCall } = await import('./call.js'); return startCall(chat.id, false); }
        openChat(chat.id);
      } catch (err) { toast(err.message); }
    });
    this.load('');
  }
  subscribe() { return [on('users', () => this.users && this.render())]; }
  async load(term) {
    try {
      const { users } = await api(`/api/users?q=${encodeURIComponent(term.trim())}`);
      C.rememberUsers(users);
      this.users = users;
      this.render();
    } catch (err) { toast(err.message); }
  }
  render() {
    const list = $(this, '#list');
    list.innerHTML = this.users.length ? this.users.map((u) => `
      <ion-item button detail="false" class="user-item" data-user="${u.id}">
        <div slot="start">${avatarOf(u, { dot: true })}</div>
        <ion-label><h2>${esc(C.fresh(u).displayName)}${checkOf(u)}${badgeOf(u)}</h2><p>@${esc(u.username)} · ${esc(lastSeenText(C.fresh(u)))}</p></ion-label>
        <ion-button slot="end" fill="clear" class="toolbar-icon" data-call="1" aria-label="Позвонить">${I.phone}</ion-button>
      </ion-item>`).join('') : '<div class="empty">Никого не найдено</div>';
  }
}

// ---------- settings ----------
class SettingsTab extends Page {
  build() {
    const opt = (o, v) => Object.entries(o).map(([id, name]) => `<ion-select-option value="${id}">${esc(name)}</ion-select-option>`).join('');
    const icon = (svg) => `<span class="setting-icon" slot="start">${svg}</span>`;
    this.innerHTML = `${tabHeader('Настройки')}
        <div id="card"></div>
        <ion-list inset>
          <ion-item button detail data-act="edit">${icon(I.user)}<ion-label>Изменить профиль</ion-label></ion-item>
        </ion-list>
        <ion-list inset>
          <ion-item>${icon(I.wave)}<ion-select id="noise" label="Шумоподавление" interface="action-sheet" cancel-text="Отмена">
            ${opt({ off: 'Выключено', standard: 'Стандартное', strong: 'Сильное (нейросеть)' })}</ion-select></ion-item>
          <ion-item>${icon(I.music)}<ion-select id="ring" label="Мелодия звонка" interface="action-sheet" cancel-text="Отмена">
            ${opt(Object.fromEntries(Object.entries(Sound.RINGTONES).map(([k, r]) => [k, r.name])))}</ion-select></ion-item>
          <ion-item>${icon(I.bell)}<ion-toggle id="msgSound" color="success">Звуки сообщений</ion-toggle></ion-item>
        </ion-list>
        <ion-list inset id="more"></ion-list>
        <ion-list inset>
          <ion-item button detail="false" data-act="logout"><ion-label color="danger">Выйти из аккаунта</ion-label></ion-item>
        </ion-list>
        <div style="height:24px"></div>
      </ion-content>`;
    $(this, '#noise').value = C.NS.mode();
    $(this, '#noise').addEventListener('ionChange', (e) => { store.set('noise', e.detail.value); toast('Шумоподавление применится к следующему звонку и голосовому'); });
    $(this, '#ring').value = store.get('ringtone', 'classic');
    $(this, '#ring').addEventListener('ionChange', (e) => { store.set('ringtone', e.detail.value); Sound.preview(e.detail.value); });
    $(this, '#msgSound').checked = store.get('msgSound', '1') === '1';
    $(this, '#msgSound').addEventListener('ionChange', (e) => store.set('msgSound', e.detail.checked ? '1' : '0'));
    this.addEventListener('click', async (e) => {
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'edit') push('ethic-profile-edit');
      if (act === 'web') Native.openExternal(C.API);
      if (act === 'admin') Native.openExternal(C.API);
      if (act === 'logout' && await confirmDialog('Выйти из аккаунта?', '', 'Выйти', true)) C.logout();
    });
    this.render();
  }
  subscribe() { return [on('users', () => this.render())]; }
  render() {
    const me = S.me;
    $(this, '#card').innerHTML = `
      <div class="profile-card">
        <div class="profile-banner" style="${bannerStyle(me)}"></div>
        <div class="profile-body">
          ${avatarOf(me)}
          <div class="profile-name">${esc(me.displayName)}${checkOf(me)}${badgeOf(me)}</div>
          <div class="profile-tag">@${esc(me.username)}</div>
        </div>
      </div>`;
    const icon = (svg) => `<span class="setting-icon" slot="start">${svg}</span>`;
    $(this, '#more').innerHTML = `
      ${me.dev ? `<ion-item button detail data-act="admin">${icon(I.shield)}<ion-label>Админ-панель (веб)</ion-label></ion-item>` : ''}
      <ion-item button detail data-act="web">${icon(I.globe)}<ion-label>Открыть веб-версию</ion-label></ion-item>
      <ion-item lines="none">${icon(I.info)}<ion-label>Версия</ion-label><ion-note slot="end">${window.ETHIC_VERSION || '1.1.0'}</ion-note></ion-item>`;
  }
}

// ---------- profile editor ----------
const BANNER_COLORS = ['#ffffff', '#a3a3a3', '#525252', '#262626', '#000000', '#1d9bf0', '#2563eb', '#dc2626', '#16a34a', '#9333ea'];
class ProfileEditPage extends Page {
  build() {
    this.innerHTML = `
      <ion-header translucent><ion-toolbar>
        <ion-buttons slot="start"><ion-back-button text="Назад"></ion-back-button></ion-buttons>
        <ion-title>Профиль</ion-title>
        <ion-buttons slot="end"><ion-button id="save" strong>Готово</ion-button></ion-buttons>
      </ion-toolbar></ion-header>
      <ion-content fullscreen>
        <div class="profile-card">
          <div class="profile-banner" id="banner"></div>
          <div class="profile-body"><span id="av"></span></div>
        </div>
        <ion-list inset>
          <ion-item button detail data-act="avatar"><ion-label>Сменить фото профиля</ion-label></ion-item>
          <ion-item button detail data-act="banner"><ion-label>Сменить баннер</ion-label></ion-item>
        </ion-list>
        <div class="swatches" id="sw"></div>
        <ion-list inset>
          <ion-item><ion-input id="name" label="Имя" label-placement="stacked" maxlength="48"></ion-input></ion-item>
          <ion-item lines="none"><ion-label color="medium">Логин</ion-label><ion-note slot="end">@${esc(S.me.username)}</ion-note></ion-item>
        </ion-list>
      </ion-content>`;
    $(this, '#name').value = S.me.displayName;
    $(this, '#save').addEventListener('click', async () => {
      const name = $(this, '#name').value.trim();
      if (name && name !== S.me.displayName) await this.patch({ displayName: name });
      nav().pop();
    });
    this.addEventListener('click', async (e) => {
      const sw = e.target.closest('[data-color]');
      if (sw) return this.patch({ banner: sw.dataset.color });
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'avatar') this.changeAvatar();
      if (act === 'banner') this.changeBanner();
    });
    this.render();
  }
  subscribe() { return [on('users', () => this.render())]; }
  render() {
    $(this, '#banner').setAttribute('style', bannerStyle(S.me));
    $(this, '#av').innerHTML = avatarOf(S.me);
    $(this, '#sw').innerHTML = BANNER_COLORS.map((c) => `<button class="swatch ${S.me.banner === c ? 'sel' : ''}" data-color="${c}" style="background:${c}"></button>`).join('');
  }
  async patch(body) {
    try {
      const { user } = await api('/api/me', { method: 'PATCH', body });
      C.rememberUsers([{ ...user, avatar: user.avatar, banner: user.banner }]);
      C.emit('users');
    } catch (err) { toast(err.message); }
  }
  async changeAvatar() {
    const act = await sheet('Фото профиля', [{ id: 'gallery', text: 'Выбрать из галереи' }, { id: 'camera', text: 'Сделать фото' }, ...(S.me.avatar ? [{ id: 'remove', text: 'Удалить фото', destructive: true }] : [])]);
    if (!act) return;
    if (act === 'remove') return this.patch({ avatar: null });
    const file = await pickFile('image/*', act === 'camera' ? 'user' : '');
    if (!file) return;
    try {
      toast('Загружаем…');
      const blob = await C.cropTo(file, 1, 512);
      const { url } = await C.upload(blob, { kind: 'image', name: 'avatar' });
      await this.patch({ avatar: url });
    } catch (err) { toast(err.message); }
  }
  async changeBanner() {
    const act = await sheet('Баннер', [{ id: 'gallery', text: 'Выбрать картинку' }, ...(S.me.banner ? [{ id: 'reset', text: 'Сбросить', destructive: true }] : [])]);
    if (!act) return;
    if (act === 'reset') return this.patch({ banner: null });
    const file = await pickFile('image/*');
    if (!file) return;
    try {
      toast('Загружаем…');
      const blob = await C.cropTo(file, 3, 1200);
      const { url } = await C.upload(blob, { kind: 'image', name: 'banner' });
      await this.patch({ banner: url });
    } catch (err) { toast(err.message); }
  }
}

// ---------- user profile ----------
class UserPage extends Page {
  build() {
    this.innerHTML = `
      <ion-header translucent><ion-toolbar>
        <ion-buttons slot="start"><ion-back-button text=""></ion-back-button></ion-buttons><ion-title>Профиль</ion-title>
      </ion-toolbar></ion-header>
      <ion-content fullscreen><div id="body"></div></ion-content>`;
    this.render();
    api(`/api/users/${this.userId}`).then(({ user }) => { C.rememberUsers([user]); this.render(); }).catch(() => {});
    this.addEventListener('click', async (e) => {
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (!act) return;
      if (act === 'edit') return push('ethic-profile-edit');
      try {
        const chat = await C.openDirect(this.userId);
        if (act === 'write') openChat(chat.id);
        else { const { startCall } = await import('./call.js'); startCall(chat.id, act === 'video'); }
      } catch (err) { toast(err.message); }
    });
  }
  subscribe() { return [on('users', () => this.render())]; }
  render() {
    const u = S.users.get(this.userId);
    if (!u) return;
    const me = u.id === S.me.id;
    const since = u.createdAt ? new Date(u.createdAt).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }) : '';
    $(this, '#body').innerHTML = `
      <div class="profile-card">
        <div class="profile-banner" style="${bannerStyle(u)}"></div>
        <div class="profile-body">
          ${avatarOf(u, { dot: true })}
          <div class="profile-name">${esc(u.displayName)}${checkOf(u)}${badgeOf(u)}</div>
          <div class="profile-tag">@${esc(u.username)} · ${esc(lastSeenText(u))}</div>
        </div>
      </div>
      <div class="profile-actions">${me
        ? '<ion-button data-act="edit">Изменить профиль</ion-button>'
        : `<ion-button data-act="write">Написать</ion-button><ion-button fill="outline" data-act="call">${I.phone}</ion-button><ion-button fill="outline" data-act="video">${I.video}</ion-button>`}</div>
      ${since ? `<ion-list inset><ion-item lines="none"><ion-label color="medium">В Ethic с</ion-label><ion-note slot="end">${since}</ion-note></ion-item></ion-list>` : ''}`;
  }
}

// ---------- group info ----------
class GroupPage extends Page {
  build() {
    this.innerHTML = `
      <ion-header translucent><ion-toolbar>
        <ion-buttons slot="start"><ion-back-button text=""></ion-back-button></ion-buttons><ion-title>Группа</ion-title>
      </ion-toolbar></ion-header>
      <ion-content fullscreen><div id="body"></div></ion-content>`;
    this.addEventListener('click', async (e) => {
      const u = e.target.closest('[data-user]');
      if (u) return push('ethic-user', { userId: Number(u.dataset.user) });
      const act = e.target.closest('[data-act]')?.dataset.act;
      const chat = S.chats.get(this.chatId);
      if (act === 'add') openPicker({ title: 'Добавить участника', exclude: chat.members.map((m) => m.id), onPick: async (uid) => {
        const { chat: updated } = await api(`/api/chats/${chat.id}/members`, { method: 'POST', body: { userId: uid } });
        C.upsertChat(updated);
      } });
      if (act === 'leave' && await leaveChat(chat)) nav().popToRoot();
    });
    this.render();
  }
  subscribe() { return [on('chats', () => this.render()), on('users', () => this.render())]; }
  render() {
    const chat = S.chats.get(this.chatId);
    if (!chat) return;
    const members = chat.members.map(C.fresh).sort((a, b) => (b.online ? 1 : 0) - (a.online ? 1 : 0));
    $(this, '#body').innerHTML = `
      <div class="msgs-top">${groupAvatar(chat)}<b>${esc(chat.title)}</b>${chat.members.length} участн.</div>
      <ion-list inset>
        <ion-item button detail="false" data-act="add"><span class="setting-icon" slot="start">${I.userPlus}</span><ion-label>Добавить участника</ion-label></ion-item>
      </ion-list>
      <ion-list inset>${members.map((m) => `
        <ion-item button detail="false" class="user-item" data-user="${m.id}">
          <div slot="start">${avatarOf(m, { dot: true })}</div>
          <ion-label><h2>${esc(m.displayName)}${checkOf(m)}${badgeOf(m)}</h2><p>${esc(lastSeenText(m))}</p></ion-label>
        </ion-item>`).join('')}</ion-list>
      <ion-list inset><ion-item button detail="false" data-act="leave"><ion-label color="danger">Покинуть группу</ion-label></ion-item></ion-list>`;
  }
}

// ---------- pickers (new group / add member / add to call) ----------
class PickerPage extends Page {
  build() {
    this.picked = new Set();
    const multi = !!this.multi;
    this.innerHTML = `
      <ion-header><ion-toolbar>
        <ion-buttons slot="start"><ion-button id="cancel">Отмена</ion-button></ion-buttons>
        <ion-title>${esc(this.title)}</ion-title>
        <ion-buttons slot="end"><ion-button id="done" strong disabled>${esc(this.doneText || 'Готово')}</ion-button></ion-buttons>
      </ion-toolbar>
      ${this.askTitle ? '<ion-toolbar><ion-input id="gt" placeholder="Название группы" maxlength="64" style="padding:0 16px"></ion-input></ion-toolbar>' : ''}
      <ion-toolbar><ion-searchbar id="sb" placeholder="Поиск" debounce="200"></ion-searchbar></ion-toolbar></ion-header>
      <ion-content><ion-list id="list" lines="full"></ion-list></ion-content>`;
    const done = $(this, '#done');
    const refresh = () => { done.disabled = !this.picked.size && !(this.askTitle && $(this, '#gt')?.value?.trim()); };
    $(this, '#cancel').addEventListener('click', () => this.closest('ion-modal').dismiss());
    $(this, '#gt')?.addEventListener('ionInput', refresh);
    $(this, '#sb').addEventListener('ionInput', (e) => this.load(e.detail.value || ''));
    $(this, '#list').addEventListener('click', (e) => {
      const item = e.target.closest('[data-user]');
      if (!item) return;
      const id = Number(item.dataset.user);
      if (multi) { if (this.picked.has(id)) this.picked.delete(id); else this.picked.add(id); }
      else { this.picked.clear(); this.picked.add(id); }
      $$(this, '[data-user]').forEach((el) => { const box = el.querySelector('ion-checkbox'); if (box) box.checked = this.picked.has(Number(el.dataset.user)); });
      Native.haptic();
      refresh();
    });
    done.addEventListener('click', async () => {
      done.disabled = true;
      try {
        await this.onDone([...this.picked], $(this, '#gt')?.value?.trim() || '');
        this.closest('ion-modal').dismiss();
      } catch (err) { toast(err.message); refresh(); }
    });
    this.load('');
  }
  async load(term) {
    try {
      const { users } = await api(`/api/users?q=${encodeURIComponent(term.trim())}`);
      C.rememberUsers(users);
      const ex = new Set(this.exclude || []);
      const list = users.filter((u) => !ex.has(u.id));
      $(this, '#list').innerHTML = list.length ? list.map((u) => `
        <ion-item button detail="false" class="user-item pick-item" data-user="${u.id}">
          <div slot="start">${avatarOf(u, { dot: true })}</div>
          <ion-label><h2>${esc(u.displayName)}${checkOf(u)}</h2><p>@${esc(u.username)}</p></ion-label>
          <ion-checkbox slot="end" ${this.picked.has(u.id) ? 'checked' : ''} style="pointer-events:none"></ion-checkbox>
        </ion-item>`).join('') : '<div class="empty">Никого не найдено</div>';
    } catch (err) { toast(err.message); }
  }
}
export function openPicker({ title, doneText, multi = false, askTitle = false, exclude = [], onPick, onDone }) {
  const el = document.createElement('ethic-picker');
  Object.assign(el, { title, doneText, multi, askTitle, exclude, onDone: onDone || (async (ids) => { if (ids[0]) await onPick(ids[0]); }) });
  return presentModal(el);
}
function openGroupNew() {
  openPicker({
    title: 'Новая группа', doneText: 'Создать', multi: true, askTitle: true,
    onDone: async (ids, title) => {
      if (!title) throw new Error('Введите название группы');
      const { chat } = await api('/api/chats/group', { method: 'POST', body: { title, memberIds: ids } });
      C.upsertChat(chat);
      setTimeout(() => openChat(chat.id), 300);
    },
  });
}

customElements.define('ethic-login', LoginPage);
customElements.define('ethic-tabs', TabsPage);
customElements.define('ethic-chats', ChatsTab);
customElements.define('ethic-people', PeopleTab);
customElements.define('ethic-settings', SettingsTab);
customElements.define('ethic-profile-edit', ProfileEditPage);
customElements.define('ethic-user', UserPage);
customElements.define('ethic-group', GroupPage);
customElements.define('ethic-picker', PickerPage);
