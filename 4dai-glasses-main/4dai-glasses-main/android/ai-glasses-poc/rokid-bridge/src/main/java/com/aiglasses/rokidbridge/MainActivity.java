package com.aiglasses.rokidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.net.wifi.WifiManager;

import com.rokid.cxr.Caps;
import com.rokid.cxr.CXRServiceBridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 17;
    private static final int HTTP_PORT = 18080;
    private static final int TARGET_WIDTH = 1280;
    private static final int TARGET_HEIGHT = 720;
    private static final int THERMAL_TARGET_WIDTH = 960;
    private static final int THERMAL_TARGET_HEIGHT = 540;
    private static final int NORMAL_JPEG_QUALITY = 70;
    private static final int THERMAL_JPEG_QUALITY = 58;
    private static final int NORMAL_TARGET_FPS = 5;
    private static final int THERMAL_TARGET_FPS = 2;
    private static final float THERMAL_TRIGGER_C = 42.0f;
    private static final float THERMAL_RECOVER_C = 38.5f;
    private static final long NORMAL_MIN_FRAME_INTERVAL_MS = 240L;
    private static final long THERMAL_MIN_FRAME_INTERVAL_MS = 500L;
    private static final long CAMERA_IDLE_TIMEOUT_MS = 10_000L;
    private static final long STREAM_WAKE_LOCK_TIMEOUT_MS = 30_000L;
    private static final long CAMERA_START_TIMEOUT_MS = 4_000L;
    private static final long THERMAL_CHECK_INTERVAL_MS = 5_000L;
    private static final String CXR_CLIENT_KEY = "rk_custom_client";
    private static final String CXR_CMD_KEY = "rk_custom_key";

    private final Object frameLock = new Object();
    private final Object cameraLock = new Object();
    private final HudState hudState = new HudState();
    private final CXRServiceBridge cxrBridge = new CXRServiceBridge();
    private OfflineCaptureController offlineCapture;
    private volatile boolean cxrConnected;
    private volatile long lastCxrCommandAtMs;
    private volatile long lastHttpClientAtMs;
    private HudView hudView;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private PowerManager.WakeLock streamWakeLock;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Range<Integer> activeFpsRange;
    private volatile boolean cameraActive;
    private volatile boolean cameraStarting;
    private volatile boolean activeThermalMode;
    private volatile boolean thermalDegraded;
    private volatile long lastImageClientAtMs;
    private volatile long cameraStartRequestedAtMs;
    private volatile long lastCameraReconfigureAtMs;
    private volatile long lastThermalCheckAtMs;
    private volatile float lastBatteryTemperatureC = -1f;
    private volatile int activeCaptureWidth;
    private volatile int activeCaptureHeight;
    private volatile String cameraError = "";
    private volatile byte[] latestJpeg;
    private volatile long latestFrameAtMs;
    private volatile long frameCount;
    private volatile boolean httpRunning;
    private Thread httpThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hudView = new HudView(this, hudState);
        setContentView(hudView);
        offlineCapture = new OfflineCaptureController(this);
        prepareWakeLock();
        startCxrBridge();
        startHttpServer();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            startBridgeForegroundService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0) {
            boolean granted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startBridgeForegroundService();
            }
            synchronized (hudState) {
                hudState.alertText = granted ? "" : "相机权限未授权";
            }
            runOnUiThread(() -> hudView.invalidate());
        }
    }

    private void startBridgeForegroundService() {
        try {
            Intent intent = new Intent(this, BridgeForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (offlineCapture != null) {
            offlineCapture.stopCurrent();
        }
        stopCamera();
        stopHttpServer();
        releaseStreamWakeLock();
        super.onDestroy();
    }

    private void startCxrBridge() {
        try {
            cxrBridge.setStatusListener(new CXRServiceBridge.StatusListener() {
                @Override
                public void onConnected(String deviceId, String deviceName, int status) {
                    cxrConnected = true;
                    synchronized (hudState) {
                        hudState.cxrConnected = true;
                    }
                }

                @Override
                public void onDisconnected() {
                    cxrConnected = false;
                    synchronized (hudState) {
                        hudState.cxrConnected = false;
                    }
                }

                @Override
                public void onConnecting(String deviceId, String deviceName, int status) {
                }

                @Override
                public void onARTCStatus(float value, boolean available) {
                }

                @Override
                public void onAudioNoise(float value) {
                }

                @Override
                public void onRokidAccountChanged(String account) {
                }
            });
            cxrBridge.subscribe(CXR_CLIENT_KEY, new CXRServiceBridge.MsgCallback() {
                @Override
                public void onReceive(String name, Caps args, byte[] bytes) {
                    Map<String, String> pairs = parseCapsPairs(args);
                    lastCxrCommandAtMs = System.currentTimeMillis();
                    synchronized (hudState) {
                        hudState.lastCxrCommandAtMs = lastCxrCommandAtMs;
                    }
                    String action = pairs.get("action");
                    if ("HUD_UPDATE".equals(action)) {
                        applyHud(pairs);
                        sendHudAck(pairs.get("request_id"));
                    } else if ("START_RECORD".equals(action)) {
                        handleStartOfflineRecord(pairs);
                    } else if ("STOP_RECORD".equals(action)) {
                        handleStopOfflineRecord(pairs);
                    } else if ("ENABLE_WIFI".equals(action)) {
                        boolean enabled = tryEnableWifi();
                        sendCommandAck(
                            pairs.get("request_id"),
                            "WIFI_ENABLE_REQUESTED",
                            enabled ? "Wi-Fi enable requested" : "Wi-Fi enable restricted"
                        );
                    } else if ("OPEN_WIFI_SETTINGS".equals(action)) {
                        openWifiSettings();
                        sendCommandAck(pairs.get("request_id"), "WIFI_SETTINGS_OPENED", "Wi-Fi settings opened");
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void startCamera() {
        ensureCameraForImageClient();
    }

    private boolean ensureCameraForImageClient() {
        lastImageClientAtMs = System.currentTimeMillis();
        acquireStreamWakeLock();
        boolean thermalMode = isThermalDegraded();
        synchronized (cameraLock) {
            if (cameraStarting &&
                System.currentTimeMillis() - cameraStartRequestedAtMs > CAMERA_START_TIMEOUT_MS) {
                stopCameraLocked();
            }
            if (cameraActive || cameraStarting) {
                if (thermalMode != activeThermalMode &&
                    System.currentTimeMillis() - lastCameraReconfigureAtMs > CAMERA_IDLE_TIMEOUT_MS) {
                    stopCameraLocked();
                    startCameraLocked(thermalMode);
                }
                return cameraActive || cameraStarting;
            }
            return startCameraLocked(thermalMode);
        }
    }

    private boolean startCameraLocked(boolean thermalMode) {
        if (offlineCapture != null && offlineCapture.isRecordingOrStarting()) {
            cameraError = "offline_recording_active";
            return false;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraError = "camera_permission_denied";
            return false;
        }
        cameraStarting = true;
        cameraError = "";
        cameraStartRequestedAtMs = System.currentTimeMillis();
        activeThermalMode = thermalMode;
        latestJpeg = null;
        latestFrameAtMs = 0L;
        lastCameraReconfigureAtMs = System.currentTimeMillis();
        cameraThread = new HandlerThread("vr-rokid-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = chooseCamera(manager);
            Size size = chooseSize(manager, cameraId, thermalMode);
            activeCaptureWidth = size.getWidth();
            activeCaptureHeight = size.getHeight();
            activeFpsRange = chooseFpsRange(manager, cameraId, thermalMode ? THERMAL_TARGET_FPS : NORMAL_TARGET_FPS);
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    synchronized (cameraLock) {
                        cameraDevice = camera;
                        cameraActive = true;
                        cameraStarting = false;
                        cameraError = "";
                    }
                    createCaptureSession();
                    scheduleCameraIdleCheck();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    synchronized (cameraLock) {
                        cameraActive = false;
                        cameraStarting = false;
                        cameraError = "camera_disconnected";
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    synchronized (cameraLock) {
                        cameraActive = false;
                        cameraStarting = false;
                        cameraError = "camera_error_" + error;
                    }
                }
            }, cameraHandler);
            return true;
        } catch (Throwable throwable) {
            cameraError = "camera_open_failed_" + throwable.getClass().getSimpleName();
            stopCameraLocked();
            return false;
        }
    }

    private String chooseCamera(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        return manager.getCameraIdList()[0];
    }

    private Size chooseSize(CameraManager manager, String cameraId, boolean thermalMode) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : null;
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        int targetWidth = thermalMode ? THERMAL_TARGET_WIDTH : TARGET_WIDTH;
        int targetHeight = thermalMode ? THERMAL_TARGET_HEIGHT : TARGET_HEIGHT;
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size size : sizes) {
            long score = Math.abs(size.getWidth() - targetWidth) + Math.abs(size.getHeight() - targetHeight);
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private Range<Integer> chooseFpsRange(CameraManager manager, String cameraId, int targetFps) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = ranges[0];
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            int upper = range.getUpper();
            int lower = range.getLower();
            int score = Math.abs(upper - targetFps) * 10 + Math.abs(lower - targetFps);
            if (upper > targetFps * 3) score += 200;
            if (score < bestScore) {
                best = range;
                bestScore = score;
            }
        }
        return best;
    }

    private void createCaptureSession() {
        try {
            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(imageReader.getSurface());
            if (activeFpsRange != null) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeFpsRange);
            }
            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                        } catch (CameraAccessException ignored) {
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                    }
                },
                cameraHandler
            );
        } catch (CameraAccessException ignored) {
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = System.currentTimeMillis();
            if (now - latestFrameAtMs < currentMinFrameIntervalMs()) return;
            byte[] jpeg = imageToJpeg(image, currentJpegQuality());
            latestJpeg = jpeg;
            latestFrameAtMs = now;
            frameCount += 1;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private byte[] imageToJpeg(Image image, int quality) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), quality, output);
        return output.toByteArray();
    }

    private byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] output = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0], width, height, output, 0, 1);
        int chromaOffset = width * height;
        copyChroma(planes[2], width / 2, height / 2, output, chromaOffset, 2);
        copyChroma(planes[1], width / 2, height / 2, output, chromaOffset + 1, 2);
        return output;
    }

    private void copyPlane(Image.Plane plane, int width, int height, byte[] output, int offset, int outputPixelStride) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] row = new byte[rowStride];
        int outputOffset = offset;
        for (int rowIndex = 0; rowIndex < height; rowIndex++) {
            int length = Math.min(rowStride, buffer.remaining());
            buffer.get(row, 0, length);
            for (int column = 0; column < width; column++) {
                int inputIndex = column * pixelStride;
                if (inputIndex < length) {
                    output[outputOffset] = row[inputIndex];
                }
                outputOffset += outputPixelStride;
            }
        }
    }

    private void copyChroma(Image.Plane plane, int width, int height, byte[] output, int offset, int outputPixelStride) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] row = new byte[rowStride];
        int outputBase = offset;
        for (int rowIndex = 0; rowIndex < height; rowIndex++) {
            int length = Math.min(rowStride, buffer.remaining());
            buffer.get(row, 0, length);
            int outputOffset = outputBase + rowIndex * width * outputPixelStride;
            for (int column = 0; column < width; column++) {
                int inputIndex = column * pixelStride;
                if (inputIndex < length && outputOffset < output.length) {
                    output[outputOffset] = row[inputIndex];
                }
                outputOffset += outputPixelStride;
            }
        }
    }

    private void stopCamera() {
        synchronized (cameraLock) {
            stopCameraLocked();
        }
    }

    private void stopCameraLocked() {
        try {
            if (captureSession != null) captureSession.close();
            if (cameraDevice != null) cameraDevice.close();
            if (imageReader != null) imageReader.close();
        } catch (Exception ignored) {
        }
        captureSession = null;
        cameraDevice = null;
        imageReader = null;
        activeFpsRange = null;
        cameraActive = false;
        cameraStarting = false;
        activeCaptureWidth = 0;
        activeCaptureHeight = 0;
        latestJpeg = null;
        latestFrameAtMs = 0L;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
    }

    private void scheduleCameraIdleCheck() {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.postDelayed(() -> {
            if (System.currentTimeMillis() - lastImageClientAtMs > CAMERA_IDLE_TIMEOUT_MS) {
                synchronized (cameraLock) {
                    if (System.currentTimeMillis() - lastImageClientAtMs > CAMERA_IDLE_TIMEOUT_MS) {
                        stopCameraLocked();
                        releaseStreamWakeLock();
                    }
                }
                return;
            }
            scheduleCameraIdleCheck();
        }, 1_000L);
    }

    private void prepareWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                streamWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                    "VisionRoute:RokidHttpStream"
                );
                streamWakeLock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void acquireStreamWakeLock() {
        try {
            if (streamWakeLock != null) {
                streamWakeLock.acquire(STREAM_WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseStreamWakeLock() {
        try {
            if (streamWakeLock != null && streamWakeLock.isHeld()) {
                streamWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private long currentMinFrameIntervalMs() {
        return isThermalDegraded() ? THERMAL_MIN_FRAME_INTERVAL_MS : NORMAL_MIN_FRAME_INTERVAL_MS;
    }

    private int currentJpegQuality() {
        return isThermalDegraded() ? THERMAL_JPEG_QUALITY : NORMAL_JPEG_QUALITY;
    }

    private boolean isThermalDegraded() {
        long now = System.currentTimeMillis();
        if (now - lastThermalCheckAtMs > THERMAL_CHECK_INTERVAL_MS) {
            lastThermalCheckAtMs = now;
            float temperatureC = readBatteryTemperatureC();
            if (temperatureC > 0f) {
                lastBatteryTemperatureC = temperatureC;
                if (!thermalDegraded && temperatureC >= THERMAL_TRIGGER_C) {
                    thermalDegraded = true;
                } else if (thermalDegraded && temperatureC <= THERMAL_RECOVER_C) {
                    thermalDegraded = false;
                }
            }
        }
        return thermalDegraded;
    }

    private float readBatteryTemperatureC() {
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return -1f;
            int raw = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            return raw > 0 ? raw / 10f : -1f;
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    private void startHttpServer() {
        httpRunning = true;
        httpThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket()) {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("0.0.0.0", HTTP_PORT));
                while (httpRunning) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handleClient(socket), "vr-rokid-http-client").start();
                }
            } catch (IOException ignored) {
            }
        }, "vr-rokid-http");
        httpThread.start();
    }

    private void stopHttpServer() {
        httpRunning = false;
        if (httpThread != null) {
            httpThread.interrupt();
            httpThread = null;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket; OutputStream output = client.getOutputStream()) {
            client.setSoTimeout(2000);
            String requestLine = readRequestLine(client);
            if (requestLine == null) return;
            discardRequestHeaders(client);
            lastHttpClientAtMs = System.currentTimeMillis();
            synchronized (hudState) {
                hudState.lastHttpClientAtMs = lastHttpClientAtMs;
            }
            String path = requestLine.split(" ")[1];
            if (path.startsWith("/mjpeg")) {
                writeMjpeg(output);
            } else if (path.startsWith("/capture")) {
                writeJpeg(output);
            } else if (path.startsWith("/capabilities")) {
                writeText(output, "200 OK", "application/json", capabilitiesJson());
            } else if (path.startsWith("/sessions/start")) {
                writeText(output, "200 OK", "application/json", startOfflineRecord(path));
            } else if (path.startsWith("/sessions/current/stop")) {
                writeText(output, "200 OK", "application/json", stopOfflineRecord());
            } else if (path.startsWith("/sessions/") && path.endsWith("/download")) {
                writeSessionDownload(output, path);
            } else if (path.startsWith("/sessions/")) {
                writeText(output, "200 OK", "application/json", sessionJson(path));
            } else if (path.startsWith("/sessions")) {
                writeText(output, "200 OK", "application/json", offlineCapture.sessionsJson());
            } else if (path.startsWith("/hud")) {
                applyHud(path);
                writeText(output, "200 OK", "application/json", "{\"code\":0,\"message\":\"ok\"}");
            } else if (path.startsWith("/wifi")) {
                if (path.contains("action=enable")) {
                    boolean enabled = tryEnableWifi();
                    writeText(output, "200 OK", "application/json", "{\"code\":0,\"message\":\"wifi_enable_requested\",\"enabled\":" + enabled + "}");
                } else {
                    openWifiSettings();
                    writeText(output, "200 OK", "application/json", "{\"code\":0,\"message\":\"wifi_settings_opened\"}");
                }
            } else {
                writeText(output, "200 OK", "application/json", statusJson());
            }
        } catch (Throwable ignored) {
        }
    }

    private String readRequestLine(Socket socket) throws IOException {
        StringBuilder builder = new StringBuilder();
        int previous = -1;
        int value;
        while ((value = socket.getInputStream().read()) >= 0) {
            if (previous == '\r' && value == '\n') break;
            builder.append((char) value);
            previous = value;
        }
        return builder.length() == 0 ? null : builder.toString().trim();
    }

    private void discardRequestHeaders(Socket socket) throws IOException {
        int previous = -1;
        int current;
        int emptyLineState = 0;
        while ((current = socket.getInputStream().read()) >= 0) {
            if (previous == '\r' && current == '\n') {
                emptyLineState += 1;
                if (emptyLineState >= 2) break;
            } else if (current != '\r') {
                emptyLineState = 0;
            }
            previous = current;
        }
    }

    private void writeMjpeg(OutputStream output) throws IOException {
        ensureCameraForImageClient();
        output.write(("HTTP/1.1 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=visionroute\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        while (httpRunning) {
            ensureCameraForImageClient();
            byte[] jpeg = latestJpeg;
            if (jpeg != null) {
                output.write(("--visionroute\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(jpeg);
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            sleep(250L);
        }
    }

    private void writeJpeg(OutputStream output) throws IOException {
        ensureCameraForImageClient();
        byte[] jpeg = waitForJpeg(6_000L);
        if (jpeg == null) {
            writeText(output, "503 Service Unavailable", "application/json", "{\"code\":1,\"message\":\"no_frame\"}");
            return;
        }
        output.write(("HTTP/1.1 200 OK\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: " + jpeg.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(jpeg);
        output.flush();
    }

    private byte[] waitForJpeg(long timeoutMs) {
        long deadlineMs = System.currentTimeMillis() + timeoutMs;
        byte[] jpeg;
        while ((jpeg = latestJpeg) == null && System.currentTimeMillis() < deadlineMs) {
            sleep(50L);
        }
        return jpeg;
    }

    private void writeText(OutputStream output, String status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + status + "\r\n" +
            "Content-Type: " + contentType + "; charset=utf-8\r\n" +
            "Connection: close\r\n" +
            "Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    private String statusJson() {
        return "{\"service\":\"rokid_bare_metal_http\"," +
            "\"state\":\"ok\"," +
            "\"app\":{\"package_name\":\"com.aiglasses.rokidbridge\"," +
            "\"version_name\":\"" + escape(BuildConfig.VERSION_NAME) + "\"," +
            "\"version_code\":" + BuildConfig.VERSION_CODE + "," +
            "\"build_time\":\"" + escape(BuildConfig.ROKID_BRIDGE_BUILD_TIME) + "\"}," +
            "\"frame_count\":" + frameCount + "," +
            "\"camera_active\":" + cameraActive + "," +
            "\"camera_starting\":" + cameraStarting + "," +
            "\"camera_error\":\"" + escape(cameraError) + "\"," +
            "\"capture_width\":" + activeCaptureWidth + "," +
            "\"capture_height\":" + activeCaptureHeight + "," +
            "\"fps_range\":\"" + escape(activeFpsRange == null ? "" : activeFpsRange.toString()) + "\"," +
            "\"jpeg_quality\":" + currentJpegQuality() + "," +
            "\"min_frame_interval_ms\":" + currentMinFrameIntervalMs() + "," +
            "\"thermal_degraded\":" + thermalDegraded + "," +
            "\"battery_temperature_c\":" + String.format(Locale.US, "%.1f", lastBatteryTemperatureC) + "," +
            "\"latest_frame_age_ms\":" + Math.max(0L, System.currentTimeMillis() - latestFrameAtMs) + "," +
            "\"cxr_connected\":" + cxrConnected + "," +
            "\"last_cxr_command_age_ms\":" + Math.max(0L, System.currentTimeMillis() - lastCxrCommandAtMs) + "," +
            "\"last_http_client_age_ms\":" + Math.max(0L, System.currentTimeMillis() - lastHttpClientAtMs) + "," +
            "\"hud_seq\":\"" + escape(hudState.hudSeq) + "\"," +
            "\"offline_capture\":" + (offlineCapture == null ? "{}" : offlineCapture.statusJson()) + "," +
            "\"imu\":{\"imu_timestamp_ms\":" + System.currentTimeMillis() + "," +
            "\"yaw_deg\":0,\"pitch_deg\":0,\"roll_deg\":0,\"accuracy\":\"heading_disabled\"}}";
    }

    private String capabilitiesJson() {
        return "{\"code\":0,\"service\":\"rokid_bare_metal_http\",\"offline_capture\":" +
            (offlineCapture == null ? "{}" : offlineCapture.capabilitiesJson()) + "}";
    }

    private String startOfflineRecord(String path) {
        stopCamera();
        Map<String, String> query = parseQuery(path);
        int width = parseInt(query.get("width"), 1280);
        int height = parseInt(query.get("height"), 720);
        int fps = parseInt(query.get("fps"), 60);
        OfflineCaptureController.StartResult result = offlineCapture.start(width, height, fps);
        return "{\"code\":" + (result.ok ? 0 : 1) +
            ",\"message\":\"" + escape(result.message) + "\"" +
            ",\"session_id\":\"" + escape(result.sessionId) + "\"" +
            ",\"offline_capture\":" + offlineCapture.statusJson() + "}";
    }

    private String stopOfflineRecord() {
        OfflineCaptureController.StopResult result = offlineCapture.stopCurrent();
        return "{\"code\":" + (result.ok ? 0 : 1) +
            ",\"message\":\"" + escape(result.message) + "\"" +
            ",\"session_id\":\"" + escape(result.sessionId) + "\"" +
            ",\"session_path\":\"" + escape(result.sessionDir == null ? "" : result.sessionDir.getAbsolutePath()) + "\"" +
            ",\"offline_capture\":" + offlineCapture.statusJson() + "}";
    }

    private String sessionJson(String path) {
        String sessionId = path.substring("/sessions/".length());
        int queryIndex = sessionId.indexOf('?');
        if (queryIndex >= 0) sessionId = sessionId.substring(0, queryIndex);
        return offlineCapture.sessionJson(sessionId);
    }

    private void writeSessionDownload(OutputStream output, String path) throws IOException {
        String sessionId = path.substring("/sessions/".length(), path.length() - "/download".length());
        int queryIndex = sessionId.indexOf('?');
        if (queryIndex >= 0) sessionId = sessionId.substring(0, queryIndex);
        output.write(("HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/zip\r\n" +
            "Content-Disposition: attachment; filename=\"" + escape(sessionId) + ".zip\"\r\n" +
            "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        if (!offlineCapture.writeSessionZip(sessionId, output)) {
            output.write("{\"code\":1,\"message\":\"session_not_found\"}".getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private void applyHud(String path) {
        applyHud(parseQuery(path));
    }

    private void applyHud(Map<String, String> query) {
        synchronized (hudState) {
            hudState.hudSeq = query.getOrDefault("hud_seq", hudState.hudSeq);
            hudState.nextAction = query.getOrDefault("next_action", hudState.nextAction);
            hudState.targetName = query.getOrDefault("target_name", hudState.targetName);
            hudState.statusText = query.getOrDefault("status_text", hudState.statusText);
            hudState.distanceToNextAction = query.getOrDefault("distance_to_next_action_m", hudState.distanceToNextAction);
            hudState.remainingDistance = query.getOrDefault("remaining_distance_m", hudState.remainingDistance);
            hudState.remainingDuration = query.getOrDefault("remaining_duration_s", hudState.remainingDuration);
            hudState.currentLocationName = query.getOrDefault("current_location_name", hudState.currentLocationName);
            hudState.alertText = query.getOrDefault("alert_text", "");
            hudState.miniMapRoute = parsePoints(query.getOrDefault("mini_map_route", ""));
            hudState.miniMapCurrent = parsePoint(query.getOrDefault("mini_map_current", ""));
            hudState.miniMapTarget = parsePoint(query.getOrDefault("mini_map_target", ""));
            hudState.lastHudAtMs = System.currentTimeMillis();
        }
        runOnUiThread(() -> hudView.invalidate());
    }

    private void sendHudAck(String requestId) {
        sendCommandAck(requestId, "HUD_ACK", "HUD updated");
    }

    private void sendCommandAck(String requestId, String event, String message) {
        try {
            Caps caps = new Caps();
            caps.write("event");
            caps.write(event);
            caps.write("ok");
            caps.write("true");
            caps.write("request_id");
            caps.write(requestId == null ? "" : requestId);
            caps.write("message");
            caps.write(message);
            cxrBridge.sendMessage(CXR_CMD_KEY, caps);
        } catch (Throwable ignored) {
        }
    }

    private void handleStartOfflineRecord(Map<String, String> pairs) {
        stopCamera();
        int width = parseInt(pairs.get("width"), 1280);
        int height = parseInt(pairs.get("height"), 720);
        int fps = parseInt(pairs.get("fps"), 60);
        OfflineCaptureController.StartResult result = offlineCapture.start(width, height, fps);
        if (result.ok) {
            sendRecordEvent(pairs.get("request_id"), "RECORD_STARTED", result.sessionId, "offline capture started");
        } else {
            sendRecordEvent(pairs.get("request_id"), "RECORD_ERROR", result.sessionId, result.message);
        }
    }

    private void handleStopOfflineRecord(Map<String, String> pairs) {
        OfflineCaptureController.StopResult result = offlineCapture.stopCurrent();
        if (result.ok) {
            sendRecordEvent(
                pairs.get("request_id"),
                "RECORD_STOPPED",
                result.sessionId,
                result.sessionDir == null ? "offline capture stopped" : result.sessionDir.getAbsolutePath()
            );
        } else {
            sendRecordEvent(pairs.get("request_id"), "RECORD_ERROR", result.sessionId, result.message);
        }
    }

    private void sendRecordEvent(String requestId, String event, String sessionId, String message) {
        try {
            Caps caps = new Caps();
            caps.write("event");
            caps.write(event);
            caps.write("ok");
            caps.write("true");
            caps.write("request_id");
            caps.write(requestId == null ? "" : requestId);
            caps.write("session_id");
            caps.write(sessionId == null ? "" : sessionId);
            caps.write("message");
            caps.write(message == null ? "" : message);
            caps.write("mode");
            caps.write("offline_camera2_imu");
            cxrBridge.sendMessage(CXR_CMD_KEY, caps);
        } catch (Throwable ignored) {
        }
    }

    private void openWifiSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private boolean tryEnableWifi() {
        try {
            Object service = getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (service instanceof WifiManager) {
                return ((WifiManager) service).setWifiEnabled(true);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private Map<String, String> parseCapsPairs(Caps caps) {
        Map<String, String> result = new HashMap<>();
        if (caps == null) return result;
        List<String> values = new ArrayList<>();
        for (int index = 0; index < caps.size(); index++) {
            values.add(capsValueToString(caps.at(index)));
        }
        for (int index = 0; index + 1 < values.size(); index += 2) {
            result.put(values.get(index), values.get(index + 1));
        }
        return result;
    }

    private String capsValueToString(Caps.Value value) {
        if (value == null) return "";
        char type = value.type();
        if (type == Caps.Value.TYPE_STRING) {
            String stringValue = value.getString();
            return stringValue == null ? "" : stringValue;
        }
        if (type == Caps.Value.TYPE_INT32 || type == Caps.Value.TYPE_UINT32) {
            return String.valueOf(value.getInt());
        }
        if (type == Caps.Value.TYPE_INT64 || type == Caps.Value.TYPE_UINT64) {
            return String.valueOf(value.getLong());
        }
        if (type == Caps.Value.TYPE_FLOAT) {
            return String.valueOf(value.getFloat());
        }
        if (type == Caps.Value.TYPE_DOUBLE) {
            return String.valueOf(value.getDouble());
        }
        return "";
    }

    private Map<String, String> parseQuery(String path) {
        Map<String, String> result = new HashMap<>();
        int index = path.indexOf('?');
        if (index < 0 || index == path.length() - 1) return result;
        String[] pairs = path.substring(index + 1).split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String key = decode(pair.substring(0, equals));
            String value = decode(pair.substring(equals + 1));
            result.put(key, value);
        }
        return result;
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private List<MapPoint> parsePoints(String value) {
        List<MapPoint> points = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return points;
        String[] pairs = value.split(";");
        for (String pair : pairs) {
            MapPoint point = parsePoint(pair);
            if (point != null) points.add(point);
        }
        return points;
    }

    private MapPoint parsePoint(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String[] parts = value.split(",");
        if (parts.length != 2) return null;
        try {
            return new MapPoint(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private static final class HudState {
        String hudSeq = "";
        String nextAction = "等待导航";
        String targetName = "未选择目标";
        String statusText = "等待导航";
        String distanceToNextAction = "";
        String remainingDistance = "";
        String remainingDuration = "";
        String currentLocationName = "";
        String alertText = "";
        long lastHudAtMs = 0L;
        boolean cxrConnected = false;
        long lastCxrCommandAtMs = 0L;
        long lastHttpClientAtMs = 0L;
        List<MapPoint> miniMapRoute = new ArrayList<>();
        MapPoint miniMapCurrent;
        MapPoint miniMapTarget;
    }

    private static final class MapPoint {
        final float x;
        final float y;

        MapPoint(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class MapViewport {
        final float left;
        final float top;
        final float right;
        final float bottom;

        MapViewport(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        static MapViewport full() {
            return new MapViewport(0f, 0f, 1000f, 1000f);
        }

        static MapViewport follow(MapPoint current, float width, float height) {
            float left = current.x - width * 0.5f;
            float top = current.y - height * 0.55f;
            left = clampRange(left, 0f, 1000f - width);
            top = clampRange(top, 0f, 1000f - height);
            return new MapViewport(left, top, left + width, top + height);
        }

        float width() {
            return Math.max(1f, right - left);
        }

        float height() {
            return Math.max(1f, bottom - top);
        }

        android.graphics.Rect toBitmapRect(Bitmap bitmap) {
            return new android.graphics.Rect(
                Math.round(left / 1000f * bitmap.getWidth()),
                Math.round(top / 1000f * bitmap.getHeight()),
                Math.round(right / 1000f * bitmap.getWidth()),
                Math.round(bottom / 1000f * bitmap.getHeight())
            );
        }

        private static float clampRange(float value, float min, float max) {
            if (max <= min) return min;
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class HudView extends View {
        private final HudState state;
        private final Bitmap mapBitmap;
        private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint routeGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint currentHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint alertPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint statusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private MapPoint renderedCurrent;

        HudView(Context context, HudState state) {
            super(context);
            this.state = state;
            this.mapBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.conference_hud_map);
            routeGlowPaint.setColor(Color.WHITE);
            routeGlowPaint.setStyle(Paint.Style.STROKE);
            routeGlowPaint.setStrokeCap(Paint.Cap.ROUND);
            routeGlowPaint.setStrokeJoin(Paint.Join.ROUND);
            routeGlowPaint.setStrokeWidth(13f);
            routeGlowPaint.setAlpha(75);
            routePaint.setColor(Color.WHITE);
            routePaint.setStyle(Paint.Style.STROKE);
            routePaint.setStrokeCap(Paint.Cap.ROUND);
            routePaint.setStrokeJoin(Paint.Join.ROUND);
            routePaint.setStrokeWidth(6f);
            currentHaloPaint.setColor(Color.WHITE);
            currentHaloPaint.setStyle(Paint.Style.FILL);
            currentPaint.setColor(Color.WHITE);
            currentPaint.setStyle(Paint.Style.FILL);
            targetPaint.setColor(Color.WHITE);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(5f);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(34f);
            textPaint.setFakeBoldText(true);
            smallTextPaint.setColor(Color.WHITE);
            smallTextPaint.setTextSize(22f);
            alertPaint.setColor(Color.WHITE);
            alertPaint.setTextSize(24f);
            alertPaint.setFakeBoldText(true);
            statusDotPaint.setColor(Color.WHITE);
            statusDotPaint.setStyle(Paint.Style.FILL);
            statusTextPaint.setColor(Color.WHITE);
            statusTextPaint.setTextSize(18f);
            statusTextPaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);
            HudSnapshot snapshot;
            synchronized (state) {
                snapshot = new HudSnapshot(state);
            }
            MapPoint current = smoothCurrent(snapshot.current);
            RectF mapRect = mapRect(snapshot);
            MapViewport viewport = mapViewport(mapRect, snapshot, current);
            canvas.drawBitmap(mapBitmap, viewport.toBitmapRect(mapBitmap), mapRect, mapPaint);
            int saved = canvas.save();
            canvas.clipRect(mapRect);
            drawRoute(canvas, mapRect, viewport, snapshot.route);
            drawTarget(canvas, mapRect, viewport, snapshot.target);
            drawCurrent(canvas, mapRect, viewport, current);
            canvas.restoreToCount(saved);
            drawStatusIcons(canvas, snapshot);
            drawText(canvas, snapshot);
            postInvalidateDelayed(80L);
        }

        private RectF mapRect(HudSnapshot snapshot) {
            boolean navigating = snapshot.hasNavigation();
            float width = getWidth() * (navigating ? 0.82f : 0.96f);
            float height = getHeight() * (navigating ? 0.50f : 0.72f);
            float left = (getWidth() - width) / 2f;
            float top = getHeight() * (navigating ? 0.14f : 0.08f);
            return new RectF(left, top, left + width, top + height);
        }

        private MapViewport mapViewport(RectF rect, HudSnapshot snapshot, MapPoint current) {
            if (!snapshot.hasNavigation() || current == null) {
                return MapViewport.full();
            }
            float visibleHeight = 440f;
            float visibleWidth = visibleHeight * rect.width() / Math.max(1f, rect.height());
            visibleWidth = Math.min(760f, Math.max(520f, visibleWidth));
            visibleHeight = Math.min(650f, Math.max(380f, visibleHeight));
            return MapViewport.follow(current, visibleWidth, visibleHeight);
        }

        private MapPoint smoothCurrent(MapPoint current) {
            if (current == null) {
                renderedCurrent = null;
                return null;
            }
            if (renderedCurrent == null) {
                renderedCurrent = current;
                return renderedCurrent;
            }
            float dx = current.x - renderedCurrent.x;
            float dy = current.y - renderedCurrent.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance > 350f) {
                renderedCurrent = current;
            } else {
                renderedCurrent = new MapPoint(
                    renderedCurrent.x + dx * 0.28f,
                    renderedCurrent.y + dy * 0.28f
                );
            }
            return renderedCurrent;
        }

        private void drawRoute(Canvas canvas, RectF rect, MapViewport viewport, List<MapPoint> route) {
            if (route.size() < 2) return;
            Path path = new Path();
            MapPoint first = route.get(0);
            path.moveTo(toX(rect, viewport, first), toY(rect, viewport, first));
            for (int index = 1; index < route.size(); index++) {
                MapPoint point = route.get(index);
                path.lineTo(toX(rect, viewport, point), toY(rect, viewport, point));
            }
            canvas.drawPath(path, routeGlowPaint);
            canvas.drawPath(path, routePaint);
        }

        private void drawTarget(Canvas canvas, RectF rect, MapViewport viewport, MapPoint target) {
            if (target == null) return;
            float x = toX(rect, viewport, target);
            float y = toY(rect, viewport, target);
            canvas.drawCircle(x, y, 13f, targetPaint);
            canvas.drawLine(x - 18f, y, x + 18f, y, targetPaint);
            canvas.drawLine(x, y - 18f, x, y + 18f, targetPaint);
        }

        private void drawCurrent(Canvas canvas, RectF rect, MapViewport viewport, MapPoint current) {
            if (current == null) return;
            float x = toX(rect, viewport, current);
            float y = toY(rect, viewport, current);
            double phase = (System.currentTimeMillis() % 1600L) / 1600.0 * Math.PI * 2.0;
            float pulse = (float) ((Math.sin(phase) + 1.0) / 2.0);
            currentHaloPaint.setAlpha((int) (55 + pulse * 105));
            canvas.drawCircle(x, y, 18f + pulse * 11f, currentHaloPaint);
            canvas.drawCircle(x, y, 9f, currentPaint);
        }

        private void drawText(Canvas canvas, HudSnapshot snapshot) {
            float y = getHeight() - (snapshot.alertText.isEmpty() ? 104f : 126f);
            canvas.drawText(buildActionText(snapshot), 24f, y, textPaint);
            canvas.drawText(buildLocationLine(snapshot), 24f, y + 34f, smallTextPaint);
            canvas.drawText(buildProgressLine(snapshot), 24f, y + 64f, smallTextPaint);
            if (!snapshot.alertText.isEmpty()) {
                canvas.drawText(snapshot.alertText, 24f, y + 94f, alertPaint);
            }
        }

        private String buildActionText(HudSnapshot snapshot) {
            if (snapshot.nextAction != null && !snapshot.nextAction.trim().isEmpty()) {
                return snapshot.nextAction;
            }
            if (snapshot.statusText != null && !snapshot.statusText.trim().isEmpty()) {
                return snapshot.statusText;
            }
            return "等待导航";
        }

        private String buildLocationLine(HudSnapshot snapshot) {
            String current = snapshot.currentLocationName == null ? "" : snapshot.currentLocationName.trim();
            String target = snapshot.targetName == null || snapshot.targetName.isEmpty() ? "未选择目标" : snapshot.targetName;
            if (!current.isEmpty()) {
                return "当前位置 " + current + " · 目标 " + target;
            }
            return target;
        }

        private String buildProgressLine(HudSnapshot snapshot) {
            List<String> parts = new ArrayList<>();
            if (!snapshot.remainingDistance.isEmpty()) {
                parts.add("剩余 " + snapshot.remainingDistance + "m");
            }
            if (!snapshot.remainingDuration.isEmpty()) {
                parts.add("预计 " + formatDuration(snapshot.remainingDuration));
            }
            if (!snapshot.distanceToNextAction.isEmpty()) {
                parts.add("下一步 " + snapshot.distanceToNextAction + "m");
            }
            if (parts.isEmpty()) {
                return snapshot.statusText == null || snapshot.statusText.isEmpty() ? "等待导航" : snapshot.statusText;
            }
            StringBuilder builder = new StringBuilder(parts.get(0));
            for (int index = 1; index < parts.size(); index++) {
                builder.append(" · ").append(parts.get(index));
            }
            return builder.toString();
        }

        private String formatDuration(String secondsText) {
            try {
                int totalSeconds = Math.max(0, Math.round(Float.parseFloat(secondsText)));
                if (totalSeconds < 60) {
                    return totalSeconds + "秒";
                }
                int minutes = totalSeconds / 60;
                int seconds = totalSeconds % 60;
                if (seconds == 0) {
                    return minutes + "分钟";
                }
                return minutes + "分" + seconds + "秒";
            } catch (NumberFormatException ignored) {
                return secondsText + "秒";
            }
        }

        private void drawStatusIcons(Canvas canvas, HudSnapshot snapshot) {
            long now = System.currentTimeMillis();
            boolean cxrActive = snapshot.cxrConnected || now - snapshot.lastCxrCommandAtMs < 10_000L;
            boolean httpActive = now - snapshot.lastHttpClientAtMs < 4_000L;
            drawStatusChip(canvas, 18f, 24f, "CXR", cxrActive);
            drawStatusChip(canvas, 88f, 24f, "HTTP", httpActive);
        }

        private void drawStatusChip(Canvas canvas, float x, float y, String label, boolean active) {
            int alpha = active ? 235 : 75;
            statusDotPaint.setAlpha(alpha);
            statusTextPaint.setAlpha(alpha);
            canvas.drawCircle(x, y - 5f, 5f, statusDotPaint);
            canvas.drawText(label, x + 10f, y, statusTextPaint);
        }

        private float toX(RectF rect, MapViewport viewport, MapPoint point) {
            return rect.left + rect.width() * (clamp(point.x) - viewport.left) / viewport.width();
        }

        private float toY(RectF rect, MapViewport viewport, MapPoint point) {
            return rect.top + rect.height() * (clamp(point.y) - viewport.top) / viewport.height();
        }

        private float clamp(float value) {
            return Math.max(0f, Math.min(1000f, value));
        }
    }

    private static final class HudSnapshot {
        final String nextAction;
        final String targetName;
        final String statusText;
        final String distanceToNextAction;
        final String remainingDistance;
        final String remainingDuration;
        final String currentLocationName;
        final String alertText;
        final List<MapPoint> route;
        final MapPoint current;
        final MapPoint target;
        final boolean cxrConnected;
        final long lastCxrCommandAtMs;
        final long lastHttpClientAtMs;

        HudSnapshot(HudState state) {
            nextAction = state.nextAction;
            targetName = state.targetName;
            statusText = state.statusText;
            distanceToNextAction = state.distanceToNextAction;
            remainingDistance = state.remainingDistance;
            remainingDuration = state.remainingDuration;
            currentLocationName = state.currentLocationName;
            alertText = state.alertText;
            route = new ArrayList<>(state.miniMapRoute);
            current = state.miniMapCurrent;
            target = state.miniMapTarget;
            cxrConnected = state.cxrConnected;
            lastCxrCommandAtMs = state.lastCxrCommandAtMs;
            lastHttpClientAtMs = state.lastHttpClientAtMs;
        }

        boolean hasNavigation() {
            return route.size() >= 2 || (target != null && nextAction != null && !nextAction.equals("等待导航"));
        }
    }
}
