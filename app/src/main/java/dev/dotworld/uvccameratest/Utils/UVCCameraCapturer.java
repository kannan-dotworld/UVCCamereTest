package dev.dotworld.uvccameratest.Utils;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

import tvi.webrtc.CapturerObserver;
import tvi.webrtc.SurfaceTextureHelper;
import tvi.webrtc.VideoCapturer;

public class UVCCameraCapturer implements VideoCapturer, Runnable, USBMonitor.OnDeviceConnectListener  {


    private static final String TAG = UVCCameraCapturer.class.getSimpleName();
    private Context applicationContext;
    private CapturerObserver frameObserver;
    private volatile Handler cameraThreadHandler;
    private int preferredWidth;
    private int preferredHeight;
    private USBMonitor usbMonitor;
    private final AtomicBoolean isCameraRunning = new AtomicBoolean();
    private UVCCamera camera;
    private Thread thread;
    private Surface surface;
    private byte[] equiData = null;

    public UVCCameraCapturer(Surface surface) {
        this.surface = surface;
    }

    private boolean isInitialized() {
        return applicationContext != null && frameObserver != null;
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver frameObserver) {
        Log.d(TAG, "initialize: ");
        if (applicationContext == null) {
            throw new IllegalArgumentException("applicationContext not set.");
        }
        if (frameObserver == null) {
            throw new IllegalArgumentException("frameObserver not set");
        }
        try {
            if (isInitialized()) {
    //            throw new IllegalStateException("already initialized");
                Log.e(TAG, "already initialized: ");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.frameObserver = frameObserver;
        this.applicationContext = applicationContext;
        this.cameraThreadHandler = surfaceTextureHelper == null ? null : surfaceTextureHelper.getHandler();
    }

    @Override
    public void onAttach(UsbDevice device) {
        Log.d(TAG, "onAttach: ");
        //access to USB device
        usbMonitor.requestPermission(device);
    }

    @Override
    public void onDettach(UsbDevice device) {
        Log.d(TAG, "onDettach: ");
        releaseCamera();

    }

    @Override
    public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
        Log.d(TAG, "onConnect: ");
        try {
            this.camera = new UVCCamera();
            this.camera.open(ctrlBlock);
            String json = camera.getSupportedSize();
            Log.d(TAG, "onConnect: camera supperted size:" + json);
            JSONObject jsonObject = new JSONObject(json);
            JSONArray formats = jsonObject.getJSONArray("formats");
            Log.d(TAG, "onConnect: formats:"+formats.toString());
            int width = UVCCamera.DEFAULT_PREVIEW_WIDTH, height = UVCCamera.DEFAULT_PREVIEW_HEIGHT, formatId = 1;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.getJSONObject(i);
                JSONArray sizes = format.getJSONArray("size");
                formatId = format.getInt("default");
                boolean foundPreferred = false;
                for (int a = 0; a < sizes.length(); a++) {
                    String sizeString = sizes.getString(a);
                    int size[] = parseSize(sizeString);
                    if (preferredWidth == size[0] && preferredHeight == size[1]) {
                        foundPreferred = true;
                    }
                    if (width < size[0]) {
                        width = size[0];
                        height = size[1];
                    }
                    if (foundPreferred) {
                        width = preferredWidth;
                        height = preferredHeight;
                    }
                }
               this.camera.setPreviewSize(width,height,formatId);
                final int[] textureHandle = new int[1];
                camera.setPreviewDisplay(surface);
                this.camera.startPreview();
                this.frameObserver.onCapturerStarted(true);

            }
        } catch (Exception e) {
            Log.e(TAG, "onConnect"+e.getMessage() );
        }

    }

    @Override
    public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        Log.d(TAG, "onDisconnect: ");
        releaseCamera();
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override
    public void onCancel(UsbDevice device) {
        Log.d(TAG, "onCancel: ");
        releaseCamera();

    }

    @Override
    public void run() {
        Log.d(TAG, "run: ");
        while (isCameraRunning.get()) {
            Log.d(TAG, "run: captureFrame");
            try {
                this.frameObserver.onCapturerStarted(true);
                this.thread.sleep(100);

            } catch (InterruptedException e) {
                Log.d(TAG, "run: ");
            }
        }

    }
    @Override
    public void startCapture(final int width, final int height, final int framerate) {
        Log.d(TAG, String.format("startCapture %d %d %d", width, height, framerate));
        if (isCameraRunning.getAndSet(true)) {
            Log.d(TAG, "startCapture: Camera has already been started.");
            return;
        }
        usbMonitor = new USBMonitor(this.applicationContext, this);
        usbMonitor.register();
        Log.d(TAG, "startCapture: "+usbMonitor.getDeviceList());

    }

    @Override
    public void stopCapture() throws InterruptedException {
        Log.d(TAG, "stopCapture: ");
        releaseCamera();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (this.usbMonitor != null) {
            usbMonitor.unregister();
            this.usbMonitor.destroy();
        }

    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        Log.d(TAG, "changeCaptureFormat:" + String.format("changeCaptureFormat %d %d %d ", width, height, framerate));

    }

    @Override
    public void dispose() {
        Log.d(TAG, "dispose: ");
        releaseCamera();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (this.usbMonitor != null) {
            this.usbMonitor.destroy();
        }

    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private synchronized void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay((Surface) null);
                camera.setStatusCallback(null);
                camera.setButtonCallback(null);
                camera.close();
                camera.destroy();
            } catch (Exception e) {
                Log.e(TAG, "releaseCamera: " + e.getMessage());
            }
        }
    }

    //width , height String to int
    private int[] parseSize(String parseData) {
        int parsedData[] = new int[2];
        String stringdata[] = parseData.split("x");
        if (stringdata.length != 2) {
            return parsedData;
        }
        parsedData[0] = Integer.parseInt(stringdata[0]);
        parsedData[1] = Integer.parseInt(stringdata[1]);
        return parsedData;

    }

}