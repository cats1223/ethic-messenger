// Copies runtime libraries from node_modules into www/vendor (the app ships them offline, no CDN).
import fs from 'node:fs';
import path from 'node:path';

const out = 'www/vendor';
fs.rmSync(out, { recursive: true, force: true });
const copy = (from, to) => {
  fs.cpSync(path.join('node_modules', from), path.join(out, to), { recursive: true });
  console.log(`vendor: ${from} -> ${to}`);
};
copy('@ionic/core/dist/ionic', 'ionic');
copy('@ionic/core/css/ionic.bundle.css', 'ionic-css/ionic.bundle.css');
copy('@ionic/core/css/palettes/dark.always.css', 'ionic-css/dark.always.css');
copy('livekit-client/dist/livekit-client.umd.js', 'livekit-client.umd.js');
copy('@sapphi-red/web-noise-suppressor/dist', 'ns');
