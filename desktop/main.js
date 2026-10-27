// Ethic Messenger for Windows: the web app in its own window, like Discord's desktop client.
// Adds a tray icon, native notifications, mic/camera access and a screen picker for screen sharing.
const { app, BrowserWindow, Tray, Menu, shell, session, desktopCapturer, ipcMain, nativeImage, Notification, dialog, net } = require('electron');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');
const path = require('path');

const SITE = 'https://176-124-220-95.sslip.io:8443';
const ORIGIN = new URL(SITE).origin;
let win = null;
let tray = null;
let quitting = false;

app.setAppUserModelId('com.ethic.messenger');

// One window only: a second launch focuses the running one.
if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => show());

function show() {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 760,
    minHeight: 520,
    backgroundColor: '#000000',
    title: 'Ethic Messenger',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    // No standard Windows frame: minimize / maximize / close are drawn inside our black top strip, like Discord.
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#000000', symbolColor: '#cfcfcf', height: TITLE_H },
    webPreferences: { contextIsolation: true, sandbox: true, spellcheck: true },
  });
  win.removeMenu();
  win.once('ready-to-show', () => win.show());
  win.webContents.on('dom-ready', addTitleBar);
  win.loadURL(SITE);

  // No connection: a local page that retries.
  win.webContents.on('did-fail-load', (_e, code, _desc, url, isMainFrame) => {
    if (isMainFrame && code !== -3) win.loadFile(path.join(__dirname, 'offline.html'), { query: { url: url || SITE } });
  });

  // Links to other sites open in the normal browser.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(ORIGIN)) shell.openExternal(url);
    return { action: url.startsWith(ORIGIN) ? 'allow' : 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(ORIGIN) && !url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url); }
  });

  // Closing the window keeps the app in the tray (calls and messages keep working).
  win.on('close', (e) => {
    if (quitting) return;
    e.preventDefault();
    win.hide();
  });

  // Unread count in the page title, e.g. "(3) Ethic Messenger" -> taskbar overlay flash.
  win.on('page-title-updated', (_e, title) => {
    const n = Number(/^\((\d+)\)/.exec(title)?.[1] || 0);
    tray?.setToolTip(n ? `Ethic Messenger — непрочитанных: ${n}` : 'Ethic Messenger');
    if (n && !win.isFocused()) win.flashFrame(true);
  });
  win.on('focus', () => win.flashFrame(false));
}

// The draggable strip at the top of the window (the system buttons sit on its right end).
const TITLE_H = 32;
const TITLE_CSS = `
  html, body { box-sizing: border-box; }
  body { padding-top: ${TITLE_H}px !important; }
  .app { height: calc(100dvh - ${TITLE_H}px) !important; }
  .modal, .settings, .lightbox { top: ${TITLE_H}px !important; }
  #ethic-titlebar { position: fixed; top: 0; left: 0; right: 0; height: ${TITLE_H}px; z-index: 2147483647; background: #000;
    -webkit-app-region: drag; user-select: none; display: flex; align-items: center; gap: 8px; padding-left: 12px;
    font: 600 12px "Segoe UI", sans-serif; color: #9a9a9a; letter-spacing: .02em; }
  #ethic-titlebar img { width: 16px; height: 16px; border-radius: 4px; }
`;
function addTitleBar() {
  const url = win.webContents.getURL();
  if (!url.startsWith(ORIGIN)) return;
  win.webContents.insertCSS(TITLE_CSS);
  win.webContents.executeJavaScript(`(() => {
    if (document.getElementById('ethic-titlebar')) return;
    const bar = document.createElement('div');
    bar.id = 'ethic-titlebar';
    bar.innerHTML = '<img src="/favicon.png" alt=""><span>Ethic Messenger</span>';
    document.body.prepend(bar);
  })()`).catch(() => {});
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'build', 'tray.png')));
  tray.setToolTip('Ethic Messenger');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Открыть Ethic Messenger', click: show },
    { type: 'separator' },
    { label: 'Выйти', click: () => { quitting = true; app.quit(); } },
  ]));
  tray.on('click', show);
}

function setupPermissions() {
  const ses = session.defaultSession;
  const allowed = new Set(['media', 'notifications', 'display-capture', 'fullscreen', 'clipboard-sanitized-write', 'speaker-selection']);
  ses.setPermissionRequestHandler((wc, permission, cb) => cb(wc.getURL().startsWith(ORIGIN) && allowed.has(permission)));
  ses.setPermissionCheckHandler((wc, permission, origin) => origin === ORIGIN && allowed.has(permission));

  // Screen sharing: Windows has no system picker, so show our own.
  ses.setDisplayMediaRequestHandler(async (_req, cb) => {
    try {
      const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 320, height: 180 }, fetchWindowIcons: false });
      const id = await pickSource(sources.filter((s) => !s.name.startsWith('Ethic Messenger') || s.id.startsWith('screen')));
      const source = sources.find((s) => s.id === id);
      if (!source) return cb({});
      // System audio is only available for whole screens on Windows.
      cb(source.id.startsWith('screen') ? { video: source, audio: 'loopback' } : { video: source });
    } catch {
      cb({});
    }
  });
}

function pickSource(sources) {
  return new Promise((resolve) => {
    const picker = new BrowserWindow({
      parent: win, modal: true, width: 760, height: 560, resizable: false, minimizable: false, maximizable: false,
      backgroundColor: '#111111', title: 'Демонстрация экрана', autoHideMenuBar: true, show: false,
      webPreferences: { preload: path.join(__dirname, 'preload-picker.js'), contextIsolation: true, sandbox: true },
    });
    picker.removeMenu();
    let done = false;
    const finish = (id) => { if (done) return; done = true; ipcMain.removeHandler('picker:choose'); resolve(id); if (!picker.isDestroyed()) picker.close(); };
    ipcMain.handle('picker:choose', (_e, id) => finish(id));
    picker.on('closed', () => finish(null));
    picker.loadFile(path.join(__dirname, 'picker.html'));
    picker.webContents.once('did-finish-load', () => {
      picker.webContents.send('picker:sources', sources.map((s) => ({ id: s.id, name: s.name, thumb: s.thumbnail.toDataURL() })));
      picker.show();
    });
  });
}

app.whenReady().then(() => {
  setupPermissions();
  createWindow();
  createTray();
  if (app.isPackaged) { setTimeout(checkUpdate, 8000); setInterval(checkUpdate, 6 * 3600_000); }
  // Development check: ETHIC_SHOT=<file> saves a screenshot of the window and quits.
  if (process.env.ETHIC_SHOT) {
    win.webContents.once('did-finish-load', () => setTimeout(async () => {
      require('fs').writeFileSync(process.env.ETHIC_SHOT, (await win.webContents.capturePage()).toPNG());
      quitting = true; app.quit();
    }, 4000));
  }
  if (!Notification.isSupported()) console.warn('notifications not supported');
});

// ---------- updates: a newer installer on GitHub releases is offered and installed in place ----------
const LATEST = 'https://api.github.com/repos/cats1223/ethic-messenger/releases/latest';
const newer = (a, b) => {
  const x = a.replace(/^v/, '').split('.').map(Number), y = b.split('.').map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0; }
  return false;
};
let offered = null;
async function checkUpdate() {
  try {
    const r = await (await net.fetch(LATEST, { headers: { Accept: 'application/vnd.github+json' } })).json();
    const exe = r.assets?.find((a) => a.name.endsWith('.exe'));
    if (!exe || !newer(r.tag_name, app.getVersion()) || offered === r.tag_name) return;
    offered = r.tag_name;
    const { response } = await dialog.showMessageBox(win, {
      type: 'info', buttons: ['Обновить', 'Позже'], defaultId: 0, cancelId: 1,
      title: 'Ethic Messenger', message: `Доступна версия ${r.tag_name.replace(/^v/, '')}`,
      detail: `Сейчас у вас ${app.getVersion()}. Обновление скачается и установится само, приложение перезапустится.`,
    });
    if (response !== 0) return;
    const file = path.join(os.tmpdir(), 'Ethic-Messenger-Setup.exe');
    const res = await net.fetch(exe.browser_download_url);
    fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()));
    spawn(file, [], { detached: true, stdio: 'ignore' }).unref();
    quitting = true;
    app.quit();
  } catch (err) { console.warn('update check failed', err.message); }
}

app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', () => {});
