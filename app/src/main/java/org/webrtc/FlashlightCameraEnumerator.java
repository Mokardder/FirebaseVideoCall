package org.webrtc;

public class FlashlightCameraEnumerator extends Camera1Enumerator {
    public FlashlightCameraEnumerator(boolean captureToTexture) {
        super(captureToTexture);
    }

    @Override
    public CameraVideoCapturer createCapturer(String deviceName, CameraVideoCapturer.CameraEventsHandler eventsHandler) {
        return new FlashlightCameraCapturer(deviceName, eventsHandler, true);
    }
}
