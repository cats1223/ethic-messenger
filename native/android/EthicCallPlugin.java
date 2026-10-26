package com.ethic.messenger;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Makes Ethic calls behave like phone calls on Android:
 *  - ringtone on the system "ring" stream (ringtone volume, silent / vibrate switch);
 *  - during a call: foreground service (mic keeps working in background, ongoing notification),
 *    communication audio mode, speaker / earpiece switch.
 */
@CapacitorPlugin(name = "EthicCall")
public class EthicCallPlugin extends Plugin {
    private static final long[] PATTERN = { 0, 800, 600 };
    static EthicCallPlugin instance;
    private MediaPlayer player;
    private Vibrator vibrator;
    private boolean inCall;

    @Override
    public void load() {
        instance = this;
    }

    // ---------- incoming ringtone ----------

    @PluginMethod
    public void ringStart(PluginCall call) {
        ringHalt();
        Context ctx = getContext();
        AudioManager am = audio();
        int mode = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
        if (mode == AudioManager.RINGER_MODE_SILENT) { call.resolve(); return; }

        vibrate(ctx);
        if (mode == AudioManager.RINGER_MODE_NORMAL && !call.getBoolean("silent", false)) {
            try {
                Uri uri;
                String wav = call.getString("wav");
                if (wav != null) {
                    File f = new File(ctx.getCacheDir(), "ring.wav");
                    try (FileOutputStream out = new FileOutputStream(f)) { out.write(Base64.decode(wav, Base64.DEFAULT)); }
                    uri = Uri.fromFile(f);
                } else {
                    uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE);
                    if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                }
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setDataSource(ctx, uri);
                player.setLooping(true);
                player.prepare();
                player.start();
            } catch (Exception e) {
                ringHalt();
                call.reject("ring failed", e);
                return;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void ringStop(PluginCall call) {
        ringHalt();
        call.resolve();
    }

    @SuppressWarnings("deprecation")
    private void vibrate(Context ctx) {
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build();
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 1), attrs);
        else vibrator.vibrate(PATTERN, 1, attrs);
    }

    private void ringHalt() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
    }

    // ---------- ongoing call ----------

    /**
     * Must run before the call's audio starts playing: the WebView picks the output stream
     * (voice call vs media) from the audio mode at the moment playback begins.
     */
    @PluginMethod
    public void callStart(PluginCall call) {
        ringHalt();
        AudioManager am = audio();
        if (am != null) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        inCall = true;
        setVolumeStream(AudioManager.STREAM_VOICE_CALL);
        setSpeakerOn(call.getBoolean("speaker", true) && !headsetConnected());
        JSObject res = new JSObject();
        res.put("speaker", isSpeakerOn());
        call.resolve(res);
    }

    /** Foreground service; started once the mic permission is granted (required on Android 14+). */
    @PluginMethod
    public void callService(PluginCall call) {
        Context ctx = getContext();
        Intent i = new Intent(ctx, CallService.class);
        i.putExtra("title", call.getString("title", "Звонок"));
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception e) {
            // Without the service the call still works while the app is open.
        }
        call.resolve();
    }

    /** Volume keys change the call volume during a call (not media). */
    private void setVolumeStream(int stream) {
        if (getActivity() != null) getActivity().runOnUiThread(() -> getActivity().setVolumeControlStream(stream));
    }

    @PluginMethod
    public void callStop(PluginCall call) {
        endCall();
        call.resolve();
    }

    @PluginMethod
    public void setSpeaker(PluginCall call) {
        setSpeakerOn(call.getBoolean("on", true));
        JSObject res = new JSObject();
        res.put("speaker", isSpeakerOn());
        call.resolve(res);
    }

    /** Called by the notification's "Завершить" button. */
    void hangupFromNotification() {
        notifyListeners("hangup", new JSObject(), true);
    }

    private void endCall() {
        Context ctx = getContext();
        ctx.stopService(new Intent(ctx, CallService.class));
        if (!inCall) return;
        inCall = false;
        setVolumeStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
        AudioManager am = audio();
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        else setSpeakerLegacy(am, false);
        am.setMode(AudioManager.MODE_NORMAL);
    }

    private AudioManager audio() {
        return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    private boolean headsetConnected() {
        AudioManager am = audio();
        if (am == null || Build.VERSION.SDK_INT < 23) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) return true;
            if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
        }
        return false;
    }

    private void setSpeakerOn(boolean on) {
        AudioManager am = audio();
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 31) {
            if (!on) { am.clearCommunicationDevice(); return; }
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { am.setCommunicationDevice(d); return; }
            }
        } else {
            setSpeakerLegacy(am, on);
        }
    }

    private boolean isSpeakerOn() {
        AudioManager am = audio();
        if (am == null) return false;
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo d = am.getCommunicationDevice();
            return d != null && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }
        return isSpeakerLegacy(am);
    }

    @SuppressWarnings("deprecation")
    private static void setSpeakerLegacy(AudioManager am, boolean on) { am.setSpeakerphoneOn(on); }

    @SuppressWarnings("deprecation")
    private static boolean isSpeakerLegacy(AudioManager am) { return am.isSpeakerphoneOn(); }

    @Override
    protected void handleOnDestroy() {
        ringHalt();
        endCall();
        if (instance == this) instance = null;
    }
}
