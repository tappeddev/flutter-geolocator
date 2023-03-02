package com.baseflow.geolocator;

import java.util.HashMap;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodChannel;

public class LogListener {
    private final MethodChannel channel;

    public LogListener(BinaryMessenger messenger) {
        channel = new MethodChannel(messenger, "flutter.baseflow.com/geolocator_android");
    }

    public void onLog(String tag, String message) {
        HashMap<String, String> payload = new HashMap<>();
        payload.put("tag", tag);
        payload.put("message", message);

        channel.invokeMethod("onLog", payload);
    }
}
