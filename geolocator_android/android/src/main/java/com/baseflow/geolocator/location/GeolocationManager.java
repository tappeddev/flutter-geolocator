package com.baseflow.geolocator.location;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baseflow.geolocator.LogListener;
import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings("deprecation")
public class GeolocationManager
    implements io.flutter.plugin.common.PluginRegistry.ActivityResultListener {

    static private final String TAG = "GeolocationManager";

    private final LogListener logListener;
  private final List<LocationClient> locationClients;

  public GeolocationManager(LogListener logListener) {
      this.logListener = logListener;
    this.locationClients = new CopyOnWriteArrayList<>();
  }

  public void getLastKnownPosition(
      Context context,
      boolean forceLocationManager,
      PositionChangedCallback positionChangedCallback,
      ErrorCallback errorCallback) {

    LocationClient locationClient = createLocationClient(context, forceLocationManager, null);
    locationClient.getLastKnownPosition(positionChangedCallback, errorCallback);
  }

  public void isLocationServiceEnabled(
      @Nullable Context context, LocationServiceListener listener) {
    if (context == null) {
      listener.onLocationServiceError(ErrorCodes.locationServicesDisabled);
    }

    LocationClient locationClient = createLocationClient(context, false, null);
    locationClient.isLocationServiceEnabled(listener);
  }

  public void startPositionUpdates(
      @NonNull LocationClient locationClient,
      @Nullable Activity activity,
      @NonNull PositionChangedCallback positionChangedCallback,
      @NonNull ErrorCallback errorCallback) {

    this.locationClients.add(locationClient);
    locationClient.startPositionUpdates(activity, positionChangedCallback, errorCallback);
  }

  public void stopPositionUpdates(@NonNull LocationClient locationClient) {
    locationClients.remove(locationClient);
    locationClient.stopPositionUpdates();
  }

  public LocationClient createLocationClient(
      Context context,
      boolean forceAndroidLocationManager,
      @Nullable LocationOptions locationOptions) {
      boolean googlePlayServicesAvailable = isGooglePlayServicesAvailable(context);

      String message = "createLocationClient: forced: " + forceAndroidLocationManager + " googlePlayServicesAvailable: " + googlePlayServicesAvailable;
      logListener.onLog(TAG, message);

    if (forceAndroidLocationManager) {
      return new LocationManagerClient(context, locationOptions, logListener);
    }

      return googlePlayServicesAvailable
        ? new FusedLocationClient(context, locationOptions, logListener)
        : new LocationManagerClient(context, locationOptions, logListener);
  }

  private boolean isGooglePlayServicesAvailable(Context context) {
    try {
      GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
      int resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context);
      return resultCode == ConnectionResult.SUCCESS;
    }
    // If the Google API class is not available conclude that the play services
    // are unavailable. This might happen when the GMS package has been excluded by
    // the app developer due to its proprietary license.
    catch(NoClassDefFoundError e) {
        logListener.onLog(TAG, "isGooglePlayServicesAvailable failed because of :" + e.getMessage());
      return false;
    }
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
    for (LocationClient client : this.locationClients) {
      if (client.onActivityResult(requestCode, resultCode)) {
        return true;
      }
    }

    return false;
  }
}
