// Ethic Messenger for Windows: the web app in its own window, like Discord's desktop client.
// Adds a tray icon, native notifications, mic/camera access and a screen picker for screen sharing.
const { app, BrowserWindow, Tray, Menu, shell, session, desktopCapturer, ipcMain, nativeImage, Notification } = require('electron');
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
    webPreferences: { contextIsolation: true, sandbox: true, spellcheck: true },
  });
  win.removeMenu();
  win.once('ready-to-show', () => win.show());
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
  if (!Notification.isSupported()) console.warn('notifications not supported');
});

app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', () => {});
