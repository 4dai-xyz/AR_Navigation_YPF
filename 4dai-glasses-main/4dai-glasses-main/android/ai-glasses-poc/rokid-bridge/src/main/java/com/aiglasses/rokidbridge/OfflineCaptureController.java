package com.aiglasses.rokidbridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class OfflineCaptureController {
    private static final String TAG = "VROfflineCapture";
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int DEFAULT_FPS = 60;
    private static final int DEFAULT_IMU_HZ = 200;
    private static final int DEFAULT_BITRATE = 12_000_000;
    private static final int DEFAULT_I_FRAME_INTERVAL_SECONDS = 1;
    private static final String MIME_AVC = "video/avc";

    private final Context context;
    private final Object lock = new Object();
    private final File sessionsRoot;
    private final SensorManager sensorManager;
    private CaptureSession currentSession;
    private String lastError = "";

    OfflineCaptureController(Context context) {
        this.context = context.getApplicationContext();
        File external = this.context.getExternalFilesDir("offline_sessions");
        sessionsRoot = external != null ? external : new File(this.context.getFilesDir(), "offline_sessions");
        if (!sessionsRoot.exists()) {
            sessionsRoot.mkdirs();
        }
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    boolean isRecordingOrStarting() {
        synchronized (lock) {
            return currentSession != null && currentSession.isActive();
        }
    }

    StartResult start(int requestedWidth, int requestedHeight, int requestedFps) {
        synchronized (lock) {
            if (currentSession != null && currentSession.isActive()) {
                return StartResult.failed("already_recording", currentSession.sessionId);
            }
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                lastError = "camera_permission_denied";
                return StartResult.failed(lastError, "");
            }
            int width = requestedWidth > 0 ? requestedWidth : DEFAULT_WIDTH;
            int height = requestedHeight > 0 ? requestedHeight : DEFAULT_HEIGHT;
            int fps = requestedFps > 0 ? requestedFps : DEFAULT_FPS;
            CaptureSession session = new CaptureSession(width, height, fps);
            currentSession = session;
            session.start();
            return StartResult.started(session.sessionId);
        }
    }

    StopResult stopCurrent() {
        CaptureSession session;
        synchronized (lock) {
            session = currentSession;
            if (session == null || !session.isActive()) {
                return StopResult.failed("not_recording", "");
            }
        }
        session.stop("user_stop");
        synchronized (lock) {
            if (currentSession == session) {
                currentSession = null;
            }
        }
        return StopResult.stopped(session.sessionId, session.sessionDir);
    }

    String statusJson() {
        CaptureSession session;
        synchronized (lock) {
            session = currentSession;
        }
        if (session == null) {
            return "{\"recording\":false,\"state\":\"idle\",\"last_error\":\"" + escape(lastError) + "\",\"latest_session\":" + latestSessionJson() + "}";
        }
        return session.statusJson();
    }

    String capabilitiesJson() {
        StringBuilder sizes = new StringBuilder("[");
        StringBuilder fpsRanges = new StringBuilder("[");
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = chooseCamera(manager);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] outputSizes = map != null ? map.getOutputSizes(MediaCodec.class) : null;
            if (outputSizes == null || outputSizes.length == 0) {
                outputSizes = map != null ? map.getOutputSizes(Surface.class) : null;
            }
            if (outputSizes != null) {
                Arrays.sort(outputSizes, (left, right) -> Integer.compare(right.getWidth() * right.getHeight(), left.getWidth() * left.getHeight()));
                int count = 0;
                for (Size size : outputSizes) {
                    if (count > 0) sizes.append(',');
                    sizes.append("{\"width\":").append(size.getWidth()).append(",\"height\":").append(size.getHeight()).append('}');
                    count += 1;
                    if (count >= 40) break;
                }
            }
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null) {
                for (int index = 0; index < ranges.length; index++) {
                    if (index > 0) fpsRanges.append(',');
                    fpsRanges.append("{\"lower\":").append(ranges[index].getLower()).append(",\"upper\":").append(ranges[index].getUpper()).append('}');
                }
            }
        } catch (Throwable throwable) {
            lastError = "capabilities_failed_" + throwable.getClass().getSimpleName();
        }
        sizes.append(']');
        fpsRanges.append(']');
        return "{\"default_width\":" + DEFAULT_WIDTH +
            ",\"default_height\":" + DEFAULT_HEIGHT +
            ",\"default_fps\":" + DEFAULT_FPS +
            ",\"default_bitrate\":" + DEFAULT_BITRATE +
            ",\"video_codec\":\"h264_avc\"" +
            ",\"strict_sync\":\"camera2_sensor_timestamp_and_sensor_event_timestamp\"" +
            ",\"sizes\":" + sizes +
            ",\"fps_ranges\":" + fpsRanges +
            ",\"imu_sensors\":" + imuSensorsJson() + "}";
    }

    String sessionsJson() {
        File[] dirs = sessionDirs();
        StringBuilder builder = new StringBuilder();
        builder.append("{\"sessions\":[");
        for (int index = 0; index < dirs.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(sessionSummaryJson(dirs[index]));
        }
        builder.append("]}");
        return builder.toString();
    }

    File sessionDir(String sessionId) {
        if (sessionId == null || sessionId.contains("/") || sessionId.contains("\\")) return null;
        File dir = new File(sessionsRoot, sessionId);
        return dir.isDirectory() ? dir : null;
    }

    String sessionJson(String sessionId) {
        File dir = sessionDir(sessionId);
        if (dir == null) return "{\"code\":1,\"message\":\"session_not_found\"}";
        File json = new File(dir, "session.json");
        if (json.isFile()) {
            return readText(json);
        }
        return sessionSummaryJson(dir);
    }

    boolean writeSessionZip(String sessionId, OutputStream output) throws IOException {
        File dir = sessionDir(sessionId);
        if (dir == null) return false;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zipDir(zip, dir, "");
            zip.finish();
        }
        return true;
    }

    private String chooseCamera(CameraManager manager) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        String[] ids = manager.getCameraIdList();
        if (ids.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
        return ids[0];
    }

    private Size chooseVideoSize(CameraManager manager, String cameraId, int targetWidth, int targetHeight) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(MediaCodec.class) : null;
        if (sizes == null || sizes.length == 0) {
            sizes = map != null ? map.getOutputSizes(Surface.class) : null;
        }
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        double targetRatio = targetWidth / (double) targetHeight;
        for (Size size : sizes) {
            double ratio = size.getWidth() / (double) size.getHeight();
            long score = Math.abs(size.getWidth() - targetWidth) + Math.abs(size.getHeight() - targetHeight);
            score += (long) (Math.abs(ratio - targetRatio) * 1000);
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
            int lower = range.getLower();
            int upper = range.getUpper();
            int score;
            if (lower <= targetFps && upper >= targetFps) {
                score = Math.abs(upper - targetFps) + Math.abs(lower - targetFps);
            } else {
                score = Math.abs(upper - targetFps) * 10 + Math.abs(lower - targetFps) + 200;
            }
            if (score < bestScore) {
                best = range;
                bestScore = score;
            }
        }
        return best;
    }

    private String imuSensorsJson() {
        StringBuilder builder = new StringBuilder("[");
        appendSensor(builder, Sensor.TYPE_GAME_ROTATION_VECTOR, "game_rotation_vector");
        appendSensor(builder, Sensor.TYPE_ROTATION_VECTOR, "rotation_vector");
        appendSensor(builder, Sensor.TYPE_GYROSCOPE, "gyroscope");
        appendSensor(builder, Sensor.TYPE_ACCELEROMETER, "accelerometer");
        return builder.append(']').toString();
    }

    private void appendSensor(StringBuilder builder, int type, String label) {
        if (sensorManager == null) return;
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor == null) return;
        if (builder.length() > 1) builder.append(',');
        builder.append("{\"type\":\"").append(label).append("\",\"name\":\"")
            .append(escape(sensor.getName())).append("\",\"min_delay_us\":")
            .append(sensor.getMinDelay()).append('}');
    }

    private File[] sessionDirs() {
        File[] dirs = sessionsRoot.listFiles(File::isDirectory);
        if (dirs == null) return new File[0];
        Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
        return dirs;
    }

    private String latestSessionJson() {
        File[] dirs = sessionDirs();
        return dirs.length == 0 ? "null" : sessionSummaryJson(dirs[0]);
    }

    private String sessionSummaryJson(File dir) {
        File video = new File(dir, "video.mp4");
        File imu = new File(dir, "imu.jsonl");
        File frames = new File(dir, "frames.csv");
        return "{\"session_id\":\"" + escape(dir.getName()) + "\"," +
            "\"video_bytes\":" + (video.isFile() ? video.length() : 0L) + "," +
            "\"imu_bytes\":" + (imu.isFile() ? imu.length() : 0L) + "," +
            "\"frames_bytes\":" + (frames.isFile() ? frames.length() : 0L) + "," +
            "\"updated_at_ms\":" + dir.lastModified() + "}";
    }

    private void zipDir(ZipOutputStream zip, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[8192];
        for (File file : files) {
            String entryName = prefix + file.getName();
            if (file.isDirectory()) {
                zipDir(zip, file, entryName + "/");
                continue;
            }
            zip.putNextEntry(new ZipEntry(entryName));
            try (FileInputStream input = new FileInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
        }
    }

    private String readText(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1024 * 1024)];
            int read = input.read(bytes);
            return read <= 0 ? "" : new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private float readBatteryTemperatureC() {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return -1f;
            int raw = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            return raw > 0 ? raw / 10f : -1f;
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    private static String nowSessionId() {
        SimpleDateFormat format = new SimpleDateFormat("'session_'yyyyMMdd_HHmmss_SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date());
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String sanitizeError(String value) {
        if (value == null) return "unknown";
        String sanitized = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('"', '\'')
            .trim();
        if (sanitized.isEmpty()) return "unknown";
        return sanitized.length() > 160 ? sanitized.substring(0, 160) : sanitized;
    }

    static final class StartResult {
        final boolean ok;
        final String message;
        final String sessionId;

        private StartResult(boolean ok, String message, String sessionId) {
            this.ok = ok;
            this.message = message;
            this.sessionId = sessionId;
        }

        static StartResult started(String sessionId) {
            return new StartResult(true, "started", sessionId);
        }

        static StartResult failed(String message, String sessionId) {
            return new StartResult(false, message, sessionId);
        }
    }

    static final class StopResult {
        final boolean ok;
        final String message;
        final String sessionId;
        final File sessionDir;

        private StopResult(boolean ok, String message, String sessionId, File sessionDir) {
            this.ok = ok;
            this.message = message;
            this.sessionId = sessionId;
            this.sessionDir = sessionDir;
        }

        static StopResult stopped(String sessionId, File dir) {
            return new StopResult(true, "stopped", sessionId, dir);
        }

        static StopResult failed(String message, String sessionId) {
            return new StopResult(false, message, sessionId, null);
        }
    }

    private final class CaptureSession implements SensorEventListener {
        final String sessionId = nowSessionId();
        final File sessionDir = new File(sessionsRoot, sessionId);
        final int requestedWidth;
        final int requestedHeight;
        final int requestedFps;
        final long commandStartWallMs = System.currentTimeMillis();
        final long anchorElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
        final long anchorWallTimeMs = System.currentTimeMillis();
        File videoFile;
        File imuFile;
        File framesFile;
        File eventsFile;
        File metadataFile;
        HandlerThread cameraThread;
        Handler cameraHandler;
        CameraDevice cameraDevice;
        CameraCaptureSession cameraCaptureSession;
        MediaCodec encoder;
        Surface encoderSurface;
        MediaMuxer muxer;
        Thread encoderThread;
        BufferedWriter imuWriter;
        BufferedWriter framesWriter;
        BufferedWriter eventsWriter;
        Range<Integer> fpsRange;
        Size actualSize = new Size(0, 0);
        volatile String state = "starting";
        volatile String error = "";
        volatile boolean stopRequested;
        volatile boolean encoderRunning;
        volatile boolean muxerStarted;
        volatile int videoTrackIndex = -1;
        volatile long recordingStartedWallMs;
        volatile long recordingStartedElapsedNs;
        volatile long recordingStoppedWallMs;
        volatile long encodedFrameCount;
        volatile long cameraCaptureResultCount;
        volatile long totalImuSampleCount;
        volatile long orientationSampleCount;
        volatile long droppedEncoderOutputCount;
        volatile long firstVideoPtsUs = -1L;
        volatile long lastVideoPtsUs = -1L;
        volatile long firstImuTimestampNs = -1L;
        volatile long lastImuTimestampNs = -1L;

        CaptureSession(int requestedWidth, int requestedHeight, int requestedFps) {
            this.requestedWidth = requestedWidth;
            this.requestedHeight = requestedHeight;
            this.requestedFps = requestedFps;
        }

        boolean isActive() {
            return "starting".equals(state) || "recording".equals(state) || "stopping".equals(state);
        }

        void start() {
            sessionDir.mkdirs();
            videoFile = new File(sessionDir, "video.mp4");
            imuFile = new File(sessionDir, "imu.jsonl");
            framesFile = new File(sessionDir, "frames.csv");
            eventsFile = new File(sessionDir, "events.jsonl");
            metadataFile = new File(sessionDir, "session.json");
            try {
                imuWriter = new BufferedWriter(new FileWriter(imuFile));
                framesWriter = new BufferedWriter(new FileWriter(framesFile));
                eventsWriter = new BufferedWriter(new FileWriter(eventsFile));
                framesWriter.write("frame_index,presentation_time_us,camera_sensor_timestamp_ns,size_bytes,flags,elapsed_realtime_ns\n");
                writeEvent("SESSION_START_REQUESTED", "requested_width", String.valueOf(requestedWidth), "requested_height", String.valueOf(requestedHeight), "requested_fps", String.valueOf(requestedFps));
                startSensors();
                startCameraAndEncoder();
            } catch (Throwable throwable) {
                Log.e(TAG, "start failed", throwable);
                fail("start_failed_" + throwable.getClass().getSimpleName() + "_" + safeErrorMessage(throwable));
            }
        }

        void startSensors() {
            if (sensorManager == null) {
                writeEvent("IMU_UNAVAILABLE", "reason", "sensor_manager_null");
                return;
            }
            registerSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            registerSensor(Sensor.TYPE_ROTATION_VECTOR);
            registerSensor(Sensor.TYPE_GYROSCOPE);
            registerSensor(Sensor.TYPE_ACCELEROMETER);
        }

        void registerSensor(int type) {
            Sensor sensor = sensorManager.getDefaultSensor(type);
            if (sensor != null) {
                int samplingPeriodUs = Math.max(1_000_000 / DEFAULT_IMU_HZ, sensor.getMinDelay());
                sensorManager.registerListener(this, sensor, samplingPeriodUs, 0);
                writeEvent(
                    "IMU_SENSOR_REGISTERED",
                    "type", sensorTypeLabel(type),
                    "name", sensor.getName(),
                    "min_delay_us", String.valueOf(sensor.getMinDelay()),
                    "sampling_period_us", String.valueOf(samplingPeriodUs)
                );
            }
        }

        void startCameraAndEncoder() throws Exception {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = chooseCamera(manager);
            actualSize = chooseVideoSize(manager, cameraId, requestedWidth, requestedHeight);
            fpsRange = chooseFpsRange(manager, cameraId, requestedFps);
            encoder = MediaCodec.createEncoderByType(MIME_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_AVC, actualSize.getWidth(), actualSize.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, requestedFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL_SECONDS);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(videoFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            encoderRunning = true;
            encoderThread = new Thread(this::drainEncoder, "vr-offline-encoder");
            encoderThread.start();

            cameraThread = new HandlerThread("vr-offline-camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
            CountDownLatch openedLatch = new CountDownLatch(1);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    openedLatch.countDown();
                    configureCameraSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    fail("camera_disconnected");
                    openedLatch.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int errorCode) {
                    camera.close();
                    fail("camera_error_" + errorCode);
                    openedLatch.countDown();
                }
            }, cameraHandler);
            if (!openedLatch.await(4, TimeUnit.SECONDS)) {
                fail("camera_open_timeout");
            }
        }

        void configureCameraSession() {
            try {
                CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                requestBuilder.addTarget(encoderSurface);
                if (fpsRange != null) {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                }
                cameraDevice.createCaptureSession(
                    Arrays.asList(encoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            cameraCaptureSession = session;
                            try {
                                recordingStartedWallMs = System.currentTimeMillis();
                                recordingStartedElapsedNs = SystemClock.elapsedRealtimeNanos();
                                state = "recording";
                                writeEvent("RECORDING_STARTED", "actual_width", String.valueOf(actualSize.getWidth()), "actual_height", String.valueOf(actualSize.getHeight()), "fps_range", fpsRange == null ? "" : fpsRange.toString());
                                session.setRepeatingRequest(requestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                                        cameraCaptureResultCount += 1;
                                        Long sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                        if (sensorTimestamp != null && cameraCaptureResultCount % 60 == 0) {
                                            writeEvent("CAMERA_CAPTURE_RESULT", "count", String.valueOf(cameraCaptureResultCount), "sensor_timestamp_ns", String.valueOf(sensorTimestamp));
                                        }
                                    }
                                }, cameraHandler);
                            } catch (Throwable throwable) {
                                Log.e(TAG, "setRepeatingRequest failed", throwable);
                                fail("camera_repeating_failed_" + throwable.getClass().getSimpleName() + "_" + safeErrorMessage(throwable));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("camera_session_config_failed");
                        }
                    },
                    cameraHandler
                );
            } catch (Throwable throwable) {
                Log.e(TAG, "createCaptureSession failed", throwable);
                fail("camera_session_failed_" + throwable.getClass().getSimpleName() + "_" + safeErrorMessage(throwable));
            }
        }

        void drainEncoder() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (encoderRunning || !stopRequested) {
                try {
                    int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (stopRequested) break;
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outputFormat = encoder.getOutputFormat();
                        videoTrackIndex = muxer.addTrack(outputFormat);
                        muxer.start();
                        muxerStarted = true;
                        writeEvent("MUXER_STARTED", "format", outputFormat.toString());
                    } else if (outputIndex >= 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                        if (encodedData == null) {
                            droppedEncoderOutputCount += 1;
                        } else if (bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                            encodedFrameCount += 1;
                            if (firstVideoPtsUs < 0) firstVideoPtsUs = bufferInfo.presentationTimeUs;
                            lastVideoPtsUs = bufferInfo.presentationTimeUs;
                            writeFrame(encodedFrameCount, bufferInfo);
                        }
                        boolean endOfStream = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(outputIndex, false);
                        if (endOfStream) break;
                    }
                } catch (Throwable throwable) {
                    error = "encoder_error_" + throwable.getClass().getSimpleName();
                    writeEvent("ENCODER_ERROR", "error", error);
                    break;
                }
            }
            encoderRunning = false;
        }

        void stop(String reason) {
            state = "stopping";
            stopRequested = true;
            writeEvent("SESSION_STOP_REQUESTED", "reason", reason);
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            try {
                if (cameraCaptureSession != null) {
                    cameraCaptureSession.stopRepeating();
                    cameraCaptureSession.abortCaptures();
                }
            } catch (Throwable ignored) {
            }
            closeCamera();
            try {
                if (encoder != null) {
                    encoder.signalEndOfInputStream();
                }
            } catch (Throwable ignored) {
            }
            if (encoderThread != null) {
                try {
                    encoderThread.join(3_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            closeEncoder();
            recordingStoppedWallMs = System.currentTimeMillis();
            state = error.isEmpty() ? "stopped" : "error";
            writeEvent("SESSION_STOPPED", "state", state, "encoded_frames", String.valueOf(encodedFrameCount), "imu_samples", String.valueOf(totalImuSampleCount));
            writeSessionMetadata();
            closeWriters();
        }

        void closeCamera() {
            try {
                if (cameraCaptureSession != null) cameraCaptureSession.close();
                if (cameraDevice != null) cameraDevice.close();
            } catch (Throwable ignored) {
            }
            cameraCaptureSession = null;
            cameraDevice = null;
            if (cameraThread != null) {
                cameraThread.quitSafely();
                cameraThread = null;
            }
        }

        void closeEncoder() {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (encoderSurface != null) encoderSurface.release();
            } catch (Throwable ignored) {
            }
            encoder = null;
            muxer = null;
            encoderSurface = null;
        }

        void closeWriters() {
            closeWriter(imuWriter);
            closeWriter(framesWriter);
            closeWriter(eventsWriter);
            imuWriter = null;
            framesWriter = null;
            eventsWriter = null;
        }

        void fail(String message) {
            error = message;
            lastError = message;
            writeEvent("SESSION_ERROR", "message", message);
            if (isActive()) {
                stop(message);
            }
        }

        String safeErrorMessage(Throwable throwable) {
            String message = throwable.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = throwable.toString();
            }
            return sanitizeError(message);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            totalImuSampleCount += 1;
            if (firstImuTimestampNs < 0) firstImuTimestampNs = event.timestamp;
            lastImuTimestampNs = event.timestamp;
            if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR || event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                orientationSampleCount += 1;
            }
            writeImu(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            writeEvent("IMU_ACCURACY_CHANGED", "type", sensorTypeLabel(sensor.getType()), "accuracy", String.valueOf(accuracy));
        }

        void writeImu(SensorEvent event) {
            BufferedWriter writer = imuWriter;
            if (writer == null) return;
            String type = sensorTypeLabel(event.sensor.getType());
            float[] quaternion = null;
            float yaw = Float.NaN;
            float pitch = Float.NaN;
            float roll = Float.NaN;
            if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR || event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                quaternion = rotationVectorToQuaternion(event.values);
                float[] matrix = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(matrix, event.values);
                SensorManager.getOrientation(matrix, orientation);
                yaw = normalizeDegrees((float) Math.toDegrees(orientation[0]));
                pitch = (float) Math.toDegrees(orientation[1]);
                roll = (float) Math.toDegrees(orientation[2]);
            }
            StringBuilder builder = new StringBuilder();
            builder.append("{\"sensor_type\":\"").append(type).append("\",");
            builder.append("\"sensor_timestamp_ns\":").append(event.timestamp).append(',');
            builder.append("\"elapsed_realtime_ns\":").append(SystemClock.elapsedRealtimeNanos()).append(',');
            builder.append("\"wall_time_ms\":").append(System.currentTimeMillis()).append(',');
            builder.append("\"accuracy\":").append(event.accuracy).append(',');
            builder.append("\"values\":[");
            for (int index = 0; index < event.values.length; index++) {
                if (index > 0) builder.append(',');
                builder.append(formatFloat(event.values[index]));
            }
            builder.append(']');
            if (quaternion != null) {
                builder.append(",\"quaternion\":[")
                    .append(formatFloat(quaternion[0])).append(',')
                    .append(formatFloat(quaternion[1])).append(',')
                    .append(formatFloat(quaternion[2])).append(',')
                    .append(formatFloat(quaternion[3])).append(']');
                builder.append(",\"yaw_deg\":").append(formatFloat(yaw));
                builder.append(",\"pitch_deg\":").append(formatFloat(pitch));
                builder.append(",\"roll_deg\":").append(formatFloat(roll));
            }
            builder.append("}\n");
            synchronized (writer) {
                try {
                    writer.write(builder.toString());
                } catch (IOException ignored) {
                }
            }
        }

        void writeFrame(long frameIndex, MediaCodec.BufferInfo info) {
            BufferedWriter writer = framesWriter;
            if (writer == null) return;
            long presentationUs = info.presentationTimeUs;
            long cameraSensorTimestampNs = presentationUs * 1000L;
            synchronized (writer) {
                try {
                    writer.write(frameIndex + "," + presentationUs + "," + cameraSensorTimestampNs + "," + info.size + "," + info.flags + "," + SystemClock.elapsedRealtimeNanos() + "\n");
                } catch (IOException ignored) {
                }
            }
        }

        void writeEvent(String event, String... pairs) {
            BufferedWriter writer = eventsWriter;
            if (writer == null) return;
            StringBuilder builder = new StringBuilder();
            builder.append("{\"event\":\"").append(event).append("\",");
            builder.append("\"wall_time_ms\":").append(System.currentTimeMillis()).append(',');
            builder.append("\"elapsed_realtime_ns\":").append(SystemClock.elapsedRealtimeNanos());
            for (int index = 0; index + 1 < pairs.length; index += 2) {
                builder.append(",\"").append(escape(pairs[index])).append("\":\"").append(escape(pairs[index + 1])).append('"');
            }
            builder.append("}\n");
            synchronized (writer) {
                try {
                    writer.write(builder.toString());
                    writer.flush();
                } catch (IOException ignored) {
                }
            }
        }

        void writeSessionMetadata() {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(metadataFile))) {
                writer.write("{\n");
                writer.write("  \"session_id\":\"" + escape(sessionId) + "\",\n");
                writer.write("  \"sync_mode\":\"camera2_mediacodec_sensor_timestamp\",\n");
                writer.write("  \"sync_quality\":\"strict_if_camera_timestamp_matches_sensor_event_clock\",\n");
                writer.write("  \"video_path\":\"video.mp4\",\n");
                writer.write("  \"imu_path\":\"imu.jsonl\",\n");
                writer.write("  \"frames_path\":\"frames.csv\",\n");
                writer.write("  \"events_path\":\"events.jsonl\",\n");
                writer.write("  \"requested_width\":" + requestedWidth + ",\n");
                writer.write("  \"requested_height\":" + requestedHeight + ",\n");
                writer.write("  \"requested_fps\":" + requestedFps + ",\n");
                writer.write("  \"actual_width\":" + actualSize.getWidth() + ",\n");
                writer.write("  \"actual_height\":" + actualSize.getHeight() + ",\n");
                writer.write("  \"fps_range\":\"" + escape(fpsRange == null ? "" : fpsRange.toString()) + "\",\n");
                writer.write("  \"video_bitrate\":" + DEFAULT_BITRATE + ",\n");
                writer.write("  \"command_start_wall_ms\":" + commandStartWallMs + ",\n");
                writer.write("  \"recording_started_wall_ms\":" + recordingStartedWallMs + ",\n");
                writer.write("  \"recording_stopped_wall_ms\":" + recordingStoppedWallMs + ",\n");
                writer.write("  \"anchor_elapsed_realtime_ns\":" + anchorElapsedRealtimeNs + ",\n");
                writer.write("  \"anchor_wall_time_ms\":" + anchorWallTimeMs + ",\n");
                writer.write("  \"encoded_frame_count\":" + encodedFrameCount + ",\n");
                writer.write("  \"camera_capture_result_count\":" + cameraCaptureResultCount + ",\n");
                writer.write("  \"imu_sample_count\":" + totalImuSampleCount + ",\n");
                writer.write("  \"orientation_sample_count\":" + orientationSampleCount + ",\n");
                writer.write("  \"actual_video_fps\":" + formatFloat((float) actualVideoFps()) + ",\n");
                writer.write("  \"actual_imu_hz\":" + formatFloat((float) actualImuHz()) + ",\n");
                writer.write("  \"error\":\"" + escape(error) + "\"\n");
                writer.write("}\n");
            } catch (IOException ignored) {
            }
        }

        String statusJson() {
            long durationMs = durationMs();
            return "{\"recording\":" + isActive() +
                ",\"state\":\"" + escape(state) + "\"" +
                ",\"session_id\":\"" + escape(sessionId) + "\"" +
                ",\"duration_ms\":" + durationMs +
                ",\"requested_width\":" + requestedWidth +
                ",\"requested_height\":" + requestedHeight +
                ",\"requested_fps\":" + requestedFps +
                ",\"actual_width\":" + actualSize.getWidth() +
                ",\"actual_height\":" + actualSize.getHeight() +
                ",\"fps_range\":\"" + escape(fpsRange == null ? "" : fpsRange.toString()) + "\"" +
                ",\"encoded_frame_count\":" + encodedFrameCount +
                ",\"camera_capture_result_count\":" + cameraCaptureResultCount +
                ",\"actual_video_fps\":" + formatFloat((float) actualVideoFps()) +
                ",\"imu_sample_count\":" + totalImuSampleCount +
                ",\"orientation_sample_count\":" + orientationSampleCount +
                ",\"actual_imu_hz\":" + formatFloat((float) actualImuHz()) +
                ",\"battery_temperature_c\":" + formatFloat(readBatteryTemperatureC()) +
                ",\"video_bytes\":" + (videoFile != null && videoFile.isFile() ? videoFile.length() : 0L) +
                ",\"error\":\"" + escape(error) + "\"}";
        }

        long durationMs() {
            long start = recordingStartedWallMs > 0 ? recordingStartedWallMs : commandStartWallMs;
            long end = recordingStoppedWallMs > 0 ? recordingStoppedWallMs : System.currentTimeMillis();
            return Math.max(0L, end - start);
        }

        double actualVideoFps() {
            if (firstVideoPtsUs >= 0 && lastVideoPtsUs > firstVideoPtsUs) {
                return encodedFrameCount * 1_000_000.0 / (lastVideoPtsUs - firstVideoPtsUs);
            }
            long duration = durationMs();
            return duration > 0 ? encodedFrameCount * 1000.0 / duration : 0.0;
        }

        double actualImuHz() {
            if (firstImuTimestampNs >= 0 && lastImuTimestampNs > firstImuTimestampNs) {
                return totalImuSampleCount * 1_000_000_000.0 / (lastImuTimestampNs - firstImuTimestampNs);
            }
            long duration = durationMs();
            return duration > 0 ? totalImuSampleCount * 1000.0 / duration : 0.0;
        }
    }

    private static void closeWriter(BufferedWriter writer) {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private static String sensorTypeLabel(int type) {
        if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) return "game_rotation_vector";
        if (type == Sensor.TYPE_ROTATION_VECTOR) return "rotation_vector";
        if (type == Sensor.TYPE_GYROSCOPE) return "gyroscope";
        if (type == Sensor.TYPE_ACCELEROMETER) return "accelerometer";
        return "sensor_" + type;
    }

    private static float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        return value < 0f ? value + 360f : value;
    }

    private static float[] rotationVectorToQuaternion(float[] values) {
        float x = values.length > 0 ? values[0] : 0f;
        float y = values.length > 1 ? values[1] : 0f;
        float z = values.length > 2 ? values[2] : 0f;
        float w;
        if (values.length > 3) {
            w = values[3];
        } else {
            float sum = x * x + y * y + z * z;
            w = sum < 1f ? (float) Math.sqrt(1f - sum) : 0f;
        }
        return new float[]{w, x, y, z};
    }

    private static String formatFloat(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return "0";
        return String.format(Locale.US, "%.3f", value);
    }
}
