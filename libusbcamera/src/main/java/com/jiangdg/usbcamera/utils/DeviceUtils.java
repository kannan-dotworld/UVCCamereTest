package com.jiangdg.usbcamera.utils;

import android.hardware.usb.UsbDevice;

public class DeviceUtils {

    public static boolean isOutsideUSBDevice(UsbDevice usbDevice) {
        return usbDevice.getProductName().equalsIgnoreCase("HD Webcam C615");
    }
}
