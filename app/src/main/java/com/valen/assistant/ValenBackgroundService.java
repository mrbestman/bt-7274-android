package com.valen.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class ValenBackgroundService extends Service {

    private static final String TAG = "ValenService";
    private static final String CHANNEL_ID = "valen_background_channel";
    private static final int NOTIFICATION_ID = 7274;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public static boolean isRunning = false;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private PowerManager.WakeLock wakeLock;
    private String serverUrl = "";

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createNotificationChannel();
        acquireWakeLock();
        trustAllCertificates();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            serverUrl = intent.getStringExtra("server_url");
            if (serverUrl == null) serverUrl = "";
        }

        startForeground(NOTIFICATION_ID, buildNotification("Listening for voice..."));

        if (!isRecording) {
            startRecording();
        }

        return START_STICKY;
    }

    private void startRecording() {
        int bufferSizeRaw = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSizeRaw == AudioRecord.ERROR || bufferSizeRaw == AudioRecord.ERROR_BAD_VALUE) {
            bufferSizeRaw = SAMPLE_RATE * 2;
        }
        final int bufferSize = bufferSizeRaw;

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stopSelf();
                return;
            }

            isRecording = true;
            audioRecord.startRecording();

            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                long lastVoiceTime = 0;
                boolean voiceDetected = false;

                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        long sum = 0;
                        for (int i = 0; i < read; i += 2) {
                            short sample = (short)((buffer[i] & 0xFF) | (buffer[i+1] << 8));
                            sum += Math.abs(sample);
                        }
                        double average = (double) sum / (read / 2);

                        if (average > 800) {
                            if (!voiceDetected) {
                                voiceDetected = true;
                                updateNotification("Voice detected! Processing...");
                            }
                            lastVoiceTime = System.currentTimeMillis();
                            pcmStream.write(buffer, 0, read);
                        } else if (voiceDetected && (System.currentTimeMillis() - lastVoiceTime > 2500)) {
                            voiceDetected = false;
                            byte[] audioData = pcmStream.toByteArray();
                            pcmStream.reset();

                            if (audioData.length > 32000) {
                                sendAudioToServer(audioData);
                            }
                            updateNotification("Listening for voice...");
                        }
                    }
                }
            }, "ValenAudioThread");
            recordingThread.start();

        } catch (SecurityException e) {
            Log.e(TAG, "Microphone permission not granted", e);
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Recording error", e);
            stopSelf();
        }
    }

    private void sendAudioToServer(byte[] pcmData) {
        if (serverUrl.isEmpty()) {
            Log.w(TAG, "No server URL configured");
            return;
        }

        new Thread(() -> {
            try {
                byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);

                String urlString = serverUrl + "/voice_chat";
                URL url = new URL(urlString);
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);

                String boundary = "----ValenBoundary" + System.currentTimeMillis();
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                OutputStream os = conn.getOutputStream();
                DataOutputStream dos = new DataOutputStream(os);

                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"speech.wav\"\r\n");
                dos.writeBytes("Content-Type: audio/wav\r\n\r\n");
                dos.write(wavData);
                dos.writeBytes("\r\n");

                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"voice\"\r\n\r\n");
                dos.writeBytes("BT-7274 (Local Clone)\r\n");

                dos.writeBytes("--" + boundary + "--\r\n");
                dos.flush();
                dos.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Server response: " + responseCode);

                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send audio", e);
            }
        }).start();
    }

    private byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int totalSize = 44 + dataSize;

        byte[] wav = new byte[totalSize];

        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt(wav, 4, totalSize - 8);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt(wav, 16, 16);
        writeShort(wav, 20, (short) 1);
        writeShort(wav, 22, (short) channels);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, (short) blockAlign);
        writeShort(wav, 34, (short) bitsPerSample);

        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt(wav, 40, dataSize);

        System.arraycopy(pcmData, 0, wav, 44, dataSize);
        return wav;
    }

    private void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte)(value & 0xFF);
        data[offset+1] = (byte)((value >> 8) & 0xFF);
        data[offset+2] = (byte)((value >> 16) & 0xFF);
        data[offset+3] = (byte)((value >> 24) & 0xFF);
    }

    private void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte)(value & 0xFF);
        data[offset+1] = (byte)((value >> 8) & 0xFF);
    }

    private void trustAllCertificates() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.e(TAG, "SSL setup failed", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Valen Background Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Continuous voice listening for BT-7274");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT-7274 VALEN AI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Valen::ServiceWakeLock");
        wakeLock.acquire(60 * 60 * 1000L);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        isRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
