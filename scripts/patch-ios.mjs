// Adds iOS privacy strings (required for mic/camera/photos) and background audio for calls.
import fs from 'node:fs';

const plistPath = 'ios/App/App/Info.plist';
let plist = fs.readFileSync(plistPath, 'utf8');
const entries = {
  NSMicrophoneUsageDescription: 'Ethic Messenger использует микрофон для звонков и голосовых сообщений.',
  NSCameraUsageDescription: 'Ethic Messenger использует камеру для видеозвонков и фото.',
  NSPhotoLibraryUsageDescription: 'Ethic Messenger открывает галерею, чтобы вы могли отправить фото.',
  NSPhotoLibraryAddUsageDescription: 'Ethic Messenger сохраняет фото из чатов в галерею.',
};
const append = (xml) => { plist = plist.replace(/<\/dict>\s*<\/plist>\s*$/, `${xml}</dict>\n</plist>\n`); };
let added = 0;
for (const [key, text] of Object.entries(entries)) {
  if (plist.includes(`<key>${key}</key>`)) continue;
  append(`\t<key>${key}</key>\n\t<string>${text}</string>\n`);
  added++;
}
if (!plist.includes('<key>UIBackgroundModes</key>')) {
  append('\t<key>UIBackgroundModes</key>\n\t<array>\n\t\t<string>audio</string>\n\t</array>\n');
  added++;
}
fs.writeFileSync(plistPath, plist);
console.log(`ios patched: ${added} Info.plist entries`);
