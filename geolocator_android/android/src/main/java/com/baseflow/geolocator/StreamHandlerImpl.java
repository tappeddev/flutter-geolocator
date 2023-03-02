package com.baseflow.geolocator;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.Nullable;

import com.baseflow.geolocator.errors.ErrorCodes;
import com.baseflow.geolocator.errors.PermissionUndefinedException;
import com.baseflow.geolocator.location.ForegroundNotificationOptions;
import com.baseflow.geolocator.location.GeolocationManager;
import com.baseflow.geolocator.location.LocationClient;
import com.baseflow.geolocator.location.LocationMapper;
import com.baseflow.geolocator.location.LocationOptions;
import com.baseflow.geolocator.permission.PermissionManager;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

class LogListener {
    private final MethodChannel channel;

    public LogListener(BinaryMessenger messenger) {
        channel = new MethodChannel(messenger, "flutter.baseflow.com/geolocator_android");
    }

    void onLog(String tag, String message) {
        HashMap<String,String> payload = new HashMap<>();
        payload.put("tag", tag);
        payload.put("message", message);

        channel.invokeMethod("onLog", payload);
    }
}

class StreamHandlerImpl implements EventChannel.StreamHandler {
  private static final String TAG = "FlutterGeolocator";

  private final PermissionManager permissionManager;
  private final LogListener logListener;

  @Nullable private EventChannel channel;
  @Nullable private Context context;
  @Nullable private Activity activity;
  @Nullable private GeolocatorLocationService foregroundLocationService;
  @Nullable private GeolocationManager geolocationManager;
  @Nullable private LocationClient locationClient;

  public StreamHandlerImpl(PermissionManager permissionManager, LogListener logListener) {
    this.permissionManager = permissionManager;
    this.logListener = logListener;

    geolocationManager = new GeolocationManager();
  }

  public void setForegroundLocationService(
      @Nullable GeolocatorLocationService foregroundLocationService) {
      log("Setting foreground service to " + foregroundLocationService);
    this.foregroundLocationService = foregroundLocationService;
  }

  public void setActivity(@Nullable Activity activity) {

    if (activity == null && locationClient != null && channel != null) {
      stopListening();
    }

    this.activity = activity;
  }

  /**
   * Registers this instance as event stream handler on the given {@code messenger}.
   *
   * <p>Stops any previously started and unstopped calls.
   *
   * <p>This should be cleaned with {@link #stopListening} once the messenger is disposed of.
   */
  void startListening(Context context, BinaryMessenger messenger) {
    if (channel != null) {
      Log.w(TAG, "Setting a event call handler before the last was disposed.");
      stopListening();
    }

    channel = new EventChannel(messenger, "flutter.baseflow.com/geolocator_updates_android");
    channel.setStreamHandler(this);
    this.context = context;
  }

  /**
   * Clears this instance from listening to method calls.
   *
   * <p>Does nothing if {@link #startListening} hasn't been called, or if we're already stopped.
   */
  void stopListening() {
    if (channel == null) {
      log("Tried to stop listening when no MethodChannel had been initialized.");
      return;
    }

    disposeListeners(false);
    channel.setStreamHandler(null);
    channel = null;
  }

  @SuppressWarnings({"ConstantConditions", "unchecked"})
  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    try {
      if (!permissionManager.hasPermission(this.context)) {
        events.error(
            ErrorCodes.permissionDenied.toString(),
            ErrorCodes.permissionDenied.toDescription(),
            null);
        return;
      }
    } catch (PermissionUndefinedException e) {
      events.error(
          ErrorCodes.permissionDefinitionsNotFound.toString(),
          ErrorCodes.permissionDefinitionsNotFound.toDescription(),
          null);
      return;
    }

    if (foregroundLocationService == null) {
      log("Location background service has not started correctly");
      return;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) arguments;
    boolean forceLocationManager = false;
    if (map != null && map.get("forceLocationManager") != null) {
      forceLocationManager = (boolean) map.get("forceLocationManager");
    }
    LocationOptions locationOptions = LocationOptions.parseArguments(map);
    ForegroundNotificationOptions foregroundNotificationOptions = null;

    if (map != null) {
      foregroundNotificationOptions =
          ForegroundNotificationOptions.parseArguments(
              (Map<String, Object>) map.get("foregroundNotificationConfig"));
    }
    if (foregroundNotificationOptions != null) {
      log("Geolocator position updates started using Android foreground service");
      foregroundLocationService.startLocationService(forceLocationManager, locationOptions, events, logListener);
      foregroundLocationService.enableBackgroundMode(foregroundNotificationOptions);
    } else {
      log("Geolocator position updates started");
      locationClient =
          geolocationManager.createLocationClient(
              context, Boolean.TRUE.equals(forceLocationManager), locationOptions);

      geolocationManager.startPositionUpdates(
          locationClient,
          activity,
          (Location location) -> events.success(LocationMapper.toHashMap(location)),
          (ErrorCodes errorCodes) ->
              events.error(errorCodes.toString(), errorCodes.toDescription(), null));
    }
  }

  @Override
  public void onCancel(Object arguments) {
    disposeListeners(true);
  }

  private void disposeListeners(boolean cancelled) {
    log("Geolocator position updates stopped");
    if (foregroundLocationService != null && foregroundLocationService.canStopLocationService(cancelled)) {
      foregroundLocationService.stopLocationService();
      foregroundLocationService.disableBackgroundMode();
    } else {
      log("There is still another flutter engine connected, not stopping location service");
    }
    if (locationClient != null && geolocationManager != null) {
      geolocationManager.stopPositionUpdates(locationClient);
      locationClient = null;
    }
  }

  private void log(String message) {
      Log.d(TAG, message);
      this.logListener.onLog(TAG, message);
  }
}
