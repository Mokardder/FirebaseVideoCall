package org.webrtc;

import android.hardware.Camera;

import java.lang.reflect.Field;

public class FlashlightCameraCapturer extends Camera1Capturer {
    public FlashlightCameraCapturer(String cameraName, CameraVideoCapturer.CameraEventsHandler eventsHandler, boolean captureToTexture) {
        super(cameraName, eventsHandler, captureToTexture);
    }

    public boolean setFlashlightActive(boolean enabled) {
        try {
            Camera camera = findCameraFromCapturer(this);
            if (camera == null) return false;

            Camera.Parameters params = camera.getParameters();
            if (params == null || params.getSupportedFlashModes() == null) return false;

            String mode = enabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF;
            if (!params.getSupportedFlashModes().contains(mode)) return false;

            params.setFlashMode(mode);
            camera.setParameters(params);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Camera findCameraFromCapturer(Object capturer) {
        Object current = capturer;
        for (int i = 0; i < 6 && current != null; i++) {
            Camera found = extractCamera(current);
            if (found != null) return found;
            current = current.getClass().getSuperclass();
        }
        return null;
    }

    private Camera extractCamera(Object target) {
        try {
            Field sessionField = null;
            Class<?> c = target.getClass();
            while (c != null && sessionField == null) {
                try {
                    sessionField = c.getDeclaredField("currentSession");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (sessionField == null) return null;
            sessionField.setAccessible(true);
            Object session = sessionField.get(target);
            if (session == null) return null;

            Class<?> s = session.getClass();
            while (s != null) {
                try {
                    Field cameraField = s.getDeclaredField("camera");
                    cameraField.setAccessible(true);
                    Object camera = cameraField.get(session);
                    if (camera instanceof Camera) {
                        return (Camera) camera;
                    }
                } catch (NoSuchFieldException ignored) {
                    s = s.getSuperclass();
                    continue;
                }
                break;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
