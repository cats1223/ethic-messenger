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
  '<uses-permission android:name="android.permission.VIBRATE" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />',
  '<uses-feature android:name="android.hardware.camera" android:required="false" />',
  '<uses-feature android:name="android.hardware.microphone" android:required="false" />',
].filter((l) => !manifest.includes(l));
manifest = manifest.replace('<application', `${lines.join('\n    ')}\n\n    <application`);
// Foreground service that keeps the microphone alive while a call runs in the background.
const service = '<service android:name=".CallService" android:exported="false" android:foregroundServiceType="microphone" />';
if (!manifest.includes('.CallService')) manifest = manifest.replace('</application>', `    ${service}
    </application>`);
fs.writeFileSync(manifestPath, manifest);

// Native call plugin (ringtone on the ring stream, call service, speaker) + register it in MainActivity.
const javaDir = 'android/app/src/main/java/com/ethic/messenger';
fs.mkdirSync(javaDir, { recursive: true });
for (const f of fs.readdirSync(javaDir)) if (/^MainActivity\./.test(f)) fs.rmSync(`${javaDir}/${f}`);
for (const f of fs.readdirSync('native/android')) fs.copyFileSync(`native/android/${f}`, `${javaDir}/${f}`);
fs.writeFileSync(`${javaDir}/MainActivity.java`, `package com.ethic.messenger;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(EthicCallPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);

const gradlePath = 'android/app/build.gradle';
const versionCode = Number(process.env.VERSION_CODE) || 1;
const versionName = process.env.VERSION_NAME || '1.0.0';
let gradle = fs.readFileSync(gradlePath, 'utf8');
gradle = gradle
  .replace(/versionCode \d+/, `versionCode ${versionCode}`)
  .replace(/versionName "[^"]*"/, `versionName "${versionName}"`);
fs.writeFileSync(gradlePath, gradle);

console.log(`android patched: ${lines.length} manifest entries, version ${versionName} (${versionCode})`);
