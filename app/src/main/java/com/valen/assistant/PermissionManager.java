package com.valen.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionManager {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private final Activity activity;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    private static final String[] ANDROID_13_PLUS_PERMISSIONS = {
        Manifest.permission.POST_NOTIFICATIONS,
    };

    private static final String[] ANDROID_12_PLUS_PERMISSIONS = {
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
    };

    public PermissionManager(Activity activity) {
        this.activity = activity;
    }

    public void requestAllPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String perm : ANDROID_13_PLUS_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(activity, perm)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(perm);
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (String perm : ANDROID_12_PLUS_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(activity, perm)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(perm);
                }
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_CODE
            );
        }
    }

    public boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasAllPermissions() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (!hasPermission(perm)) return false;
        }
        return true;
    }
}
