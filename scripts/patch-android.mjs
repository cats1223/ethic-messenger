// Adds what the generated Android project needs for calls/voice messages
// and stamps the version so newer APKs install over older ones.
import fs from 'node:fs';

const manifestPath = 'android/app/src/main/AndroidManifest.xml';
let manifest = fs.readFileSync(manifestPath, 'utf8');
const lines = [
  '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
  '<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />',
  '<uses-permission android:name="android.permission.CAMERA" />',
  '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
  '<uses-permission android:name="android.permission.WAKE_LOCK" />',
  '<uses-feature android:name="android.hardware.camera" android:required="false" />',
  '<uses-feature android:name="android.hardware.microphone" android:required="false" />',
].filter((l) => !manifest.includes(l));
manifest = manifest.replace('<application', `${lines.join('\n    ')}\n\n    <application`);
fs.writeFileSync(manifestPath, manifest);

const gradlePath = 'android/app/build.gradle';
const versionCode = Number(process.env.VERSION_CODE) || 1;
const versionName = process.env.VERSION_NAME || '1.0.0';
let gradle = fs.readFileSync(gradlePath, 'utf8');
gradle = gradle
  .replace(/versionCode \d+/, `versionCode ${versionCode}`)
  .replace(/versionName "[^"]*"/, `versionName "${versionName}"`);
fs.writeFileSync(gradlePath, gradle);

console.log(`android patched: ${lines.length} manifest entries, version ${versionName} (${versionCode})`);
