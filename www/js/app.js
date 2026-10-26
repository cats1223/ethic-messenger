// Ethic Messenger app — entry point.
import * as C from './core.js';
import { toast } from './ui.js';
import { nav } from './page.js';
import './screens.js';
import './chat.js';
import './call.js';

window.ETHIC_VERSION = '1.1.1';
const { on, Native, Sound } = C;

on('toast', (msg) => toast(msg));
on('logout', () => C.logout());
// iOS only lets WebAudio play after a user gesture.
document.addEventListener('touchstart', () => Sound.unlock(), { once: true, passive: true });
document.addEventListener('click', () => Sound.unlock(), { once: true });

function setupNative() {
  if (!Native.on) return;
  const StatusBar = Native.plugin('StatusBar');
  StatusBar?.setStyle({ style: 'DARK' }).catch(() => {});
  if (Native.platform() === 'android') StatusBar?.setBackgroundColor({ color: '#000000' }).catch(() => {});
  // Android back button: close the top overlay, then go back, then leave the app.
  Native.plugin('App')?.addListener('backButton', async () => {
    const overlays = [...document.querySelectorAll('ion-modal, ion-action-sheet, ion-alert')];
    const top = overlays[overlays.length - 1];
    if (top) return top.dismiss();
    if (await nav().canGoBack()) return nav().pop();
    Native.plugin('App')?.minimizeApp?.();
  });
}

async function boot() {
  await customElements.whenDefined('ion-nav');
  setupNative();
  const ok = await C.restore();
  await nav().setRoot(ok ? 'ethic-tabs' : 'ethic-login');
  Native.plugin('SplashScreen')?.hide().catch(() => {});
}
boot();
