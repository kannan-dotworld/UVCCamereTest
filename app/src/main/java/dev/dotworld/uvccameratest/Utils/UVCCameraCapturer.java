package dev.dotworld.uvccameratest.Utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;


import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import tvi.webrtc.CapturerObserver;
import tvi.webrtc.SurfaceTextureHelper;
import tvi.webrtc.VideoCapturer;


public class UVCCameraCapturer implements VideoCapturer, Runnable, USBMonitor.OnDeviceConnectListener, IFrameCallback {

    private static final String TAG = "UVCCameraCapturer";

    private Thread thread;
    private CapturerObserver frameObserver;
    private Context applicationContext;
    private volatile Handler cameraThreadHandler;
    private final AtomicBoolean isCameraRunning = new AtomicBoolean();
    private UVCCamera camera;
    private int preferredWidth;
    private int preferredHeight;
    private final Surface surface;

    private USBMonitor usbMonitor;

    private Surface previewSurface;

    private byte[] equiData = null;

    public UVCCameraCapturer(Surface surface) {

        this.surface = surface;
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver frameObserver) {
        Log.d("UVCCameraCapturer", "initialize");

        if (applicationContext == null) {
            throw new IllegalArgumentException("applicationContext not set.");
        }
        if (frameObserver == null) {
            throw new IllegalArgumentException("frameObserver not set.");
        }
        if (isInitialized()) {
            throw new IllegalStateException("Already initialized");
        }
        this.frameObserver = frameObserver;
        this.applicationContext = applicationContext;

        this.cameraThreadHandler = surfaceTextureHelper == null ? null : surfaceTextureHelper.getHandler();
    }


    public void startCapture(final int width, final int height, final int framerate) {
        Log.d("UVCCameraCapturer", String.format("startCapture %d %d %d", width, height, framerate));
        preferredWidth = width;
        preferredHeight = height;

        if (isCameraRunning.getAndSet(true)) {
            Log.e(TAG, "Camera has already been started.");
            return;
        }

        usbMonitor = new USBMonitor(this.applicationContext, this);
        usbMonitor.register();
        Log.d(TAG, "startCapture: "+usbMonitor.getDeviceList());
    }

    public void stopCapture() throws InterruptedException {
        Log.d("UVCCameraCapturer", "stopCapture");
        releaseCamera();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (this.usbMonitor != null) {
            usbMonitor.unregister();
            this.usbMonitor.destroy();
        }
    }

    public void changeCaptureFormat(int width, int height, int framerate) {
        Log.d("UVCCameraCapturer", String.format("changeCaptureFormat %d %d %d", width, height, framerate));
    }

    public void dispose() {
        Log.d("UVCCameraCapturer", "dispose");
        releaseCamera();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (this.usbMonitor != null) {
            this.usbMonitor.destroy();
        }
    }

    public boolean isScreencast() {
        return false;
    }

    public void run() {
        int width = 1280;
        int height = 720;

        int start = 0;
        byte[] data = new byte[(width*height*3)/2];
        for (int i = 0; i < width*height; i++) {
            data[i] = (byte)76;
        }

        for (int i = width*height; i < data.length; i += 2) {
            data[i] = (byte)255;
            data[i+1] = (byte)84;
        }

        byte[] dst = new byte[data.length];

        while (isCameraRunning.get()) {
            Log.d("UVCCameraCapturer", "captureFrame");
            try {
                this.frameObserver.onCapturerStarted(true); //me
                this.thread.sleep(100);

            } catch (InterruptedException e) {
                Log.d("UVCCameraCapturer", "InterruptedException");
            }
        }
    }

    private boolean isInitialized() {
        return applicationContext != null && frameObserver != null;
    }

    private boolean maybePostOnCameraThread(Runnable runnable) {
        return maybePostDelayedOnCameraThread(0 /* delayMs */, runnable);
    }
    private boolean maybePostDelayedOnCameraThread(int delayMs, Runnable runnable) {
        return cameraThreadHandler != null && isCameraRunning.get()
                && cameraThreadHandler.postAtTime(
                runnable, this /* token */, SystemClock.uptimeMillis() + delayMs);
    }


    @Override
    public void onAttach(final UsbDevice device) {
        //Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        Log.v(TAG, "onAttach:");
        usbMonitor.requestPermission(device);
    }

    @Override
    public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
        Log.v(TAG, "onConnect:");
        try {
            this.camera = new UVCCamera();
            this.camera.open(ctrlBlock);

            // figure out width, height, and format
            String json = camera.getSupportedSize();
            Log.d(TAG, "onConnect:  camera supported sizes : "+json);
            JSONObject obj = new JSONObject(json);
            JSONArray formats = obj.getJSONArray("formats");

            int width = UVCCamera.DEFAULT_PREVIEW_WIDTH, height = UVCCamera.DEFAULT_PREVIEW_HEIGHT, formatId = 1;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.getJSONObject(i);
                formatId = format.getInt("default");
                JSONArray sizes = format.getJSONArray("size");
                boolean foundPreferred = false;
                for (int j = 0; j < sizes.length(); j++) {
                    String sizeString = sizes.getString(j);
                    int size[] = parseSize(sizeString);

                    if (preferredWidth == size[0] && preferredHeight == size[1]) {
                        foundPreferred = true;
                    }

                    // find the largest
                    if (width < size[0]) {
                        width = size[0];
                        height = size[1];
                    }
                }

                if (foundPreferred) {
                    width = preferredWidth;
                    height = preferredHeight;
                }
            }

            this.camera.setPreviewSize(width, height, formatId);
            this.camera.setFrameCallback(this, UVCCamera.PIXEL_FORMAT_YUV420SP);

            final int[] textureHandle = new int[1];
            GLES20.glGenTextures(1, textureHandle, 0);
            final SurfaceTexture st = new SurfaceTexture(textureHandle[0], false);
            camera.setPreviewDisplay(surface);

            this.camera.startPreview();
            this.frameObserver.onCapturerStarted(true);
        }
        catch (UnsupportedOperationException e) {
            Log.e(TAG, "Unable to connect to camera: " + e.toString());
        }
        catch (JSONException e) {
            Log.e(TAG, "JSON exception: " + e.toString());
        }
    }

    @Override
    public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
        Log.v(TAG, "onDisconnect:");
        releaseCamera();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    @Override
    public void onDettach(final UsbDevice device) {
//			Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        Log.v(TAG, "onDettach:");
        releaseCamera();
    }

    @Override
    public void onCancel(final UsbDevice device) {
        Log.v(TAG, "onCancel:");
        releaseCamera();
    }

    public void onFrame(ByteBuffer frame) {
//        Log.v(TAG, "onFrame:");
        byte data[] = new byte[frame.remaining()];
        frame.get(data);
        long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

        try {
            if (equiData == null)
                equiData = new byte[(1280 * 720 * 3)/2];
        } catch (Exception e) {
        }
    }

    private synchronized void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay((Surface)null);
                camera.setStatusCallback(null);
                camera.setButtonCallback(null);
                camera.close();
                camera.destroy();
            } catch (final Exception e) {
            }
            camera = null;
        }
    }

    // parse string that looks like "1280x720"
    private int[] parseSize(String size) {
        int retval[] = new int[2];
        String vals[] = size.split("x");
        if (vals.length != 2) {
            return retval;
        }
        retval[0] = Integer.parseInt(vals[0]);
        retval[1] = Integer.parseInt(vals[1]);
        return retval;
    }

}