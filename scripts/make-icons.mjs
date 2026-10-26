// Builds app icons, splash screens and the site logo from assets/logo-source.webp.
// The ring (without the wordmark) is used for icons; the full logo for splash screens.
import fs from 'node:fs';
import sharp from 'sharp';

const SRC = 'assets/logo-source.webp';
const RING = { left: 328, top: 177, width: 600, height: 600 }; // square around the ring, text excluded
const FULL = { left: 250, top: 170, width: 754, height: 900 }; // ring + "Ethic MESSENGER"
const BLACK = { r: 0, g: 0, b: 0, alpha: 1 };

const ring = (size) => sharp(SRC).extract(RING).resize(size, size).toBuffer();
const onBlack = async (canvas, inner, innerSize) =>
  sharp({ create: { width: canvas, height: canvas, channels: 4, background: BLACK } })
    .composite([{ input: await inner(innerSize), gravity: 'center' }])
    .png();

fs.mkdirSync('assets', { recursive: true });
// iOS + legacy Android icon: ring fills most of the square.
await (await onBlack(1024, ring, 820)).toFile('assets/icon-only.png');
// Android adaptive icon: keep the ring inside the 66% safe zone.
await (await onBlack(1024, ring, 600)).toFile('assets/icon-foreground.png');
await sharp({ create: { width: 1024, height: 1024, channels: 4, background: BLACK } }).png().toFile('assets/icon-background.png');
// Splash: the whole logo, centered.
const fullLogo = (w) => sharp(SRC).extract(FULL).resize({ width: w }).toBuffer();
for (const name of ['splash.png', 'splash-dark.png']) {
  await sharp({ create: { width: 2732, height: 2732, channels: 4, background: BLACK } })
    .composite([{ input: await fullLogo(1000), gravity: 'center' }]).png().toFile(`assets/${name}`);
}

// Site logo (favicon, header, login screen) — same ring on black, rounded by CSS.
const site = '../app/public';
await (await onBlack(512, ring, 440)).toFile(`${site}/logo.png`);
await (await onBlack(180, ring, 156)).toFile(`${site}/apple-touch-icon.png`);
await (await onBlack(64, ring, 58)).toFile(`${site}/favicon.png`);
await sharp(SRC).extract(FULL).resize({ width: 600 }).png().toFile(`${site}/logo-full.png`);
console.log('icons ready');
