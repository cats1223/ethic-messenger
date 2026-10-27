// Bridge for the screen picker window: receives the list of screens/windows and returns the choice.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('picker', {
  onSources: (fn) => ipcRenderer.on('picker:sources', (_e, list) => fn(list)),
  choose: (id) => ipcRenderer.invoke('picker:choose', id),
});
