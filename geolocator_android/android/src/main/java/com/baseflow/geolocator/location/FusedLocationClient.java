package com.baseflow.geolocator.location;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.IntentSender;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baseflow.geolocator.LogListener;
import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class FusedLocationClient implements LocationClient {
  private static final String TAG = "FlutterGeolocator";

  private final Context context;
  private final LocationCallback locationCallback;
  private final FusedLocationProviderClient fusedLocationProviderClient;
  private final NmeaClient nmeaClient;
  private final int activityRequestCode;

  @Nullable private ScheduledFuture<?> checker;
  @Nullable private final LocationOptions locationOptions;

  @Nullable private ErrorCallback errorCallback;
  @Nullable private PositionChangedCallback positionChangedCallback;
    private final LogListener logListener;

    public FusedLocationClient(@NonNull Context context, @Nullable LocationOptions locationOptions, @NonNull LogListener logListener) {
        this.logListener = logListener;
    this.context = context;
    this.fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context);
    this.locationOptions = locationOptions;
    this.nmeaClient = new NmeaClient(context, locationOptions);
    this.activityRequestCode = generateActivityRequestCode();

    locationCallback =
        new LocationCallback() {
          @Override
          public synchronized void onLocationResult(@NonNull LocationResult locationResult) {
              log("onLocationResult");
            if (positionChangedCallback == null) {
                log("LocationCallback was called with empty locationResult or no positionChangedCallback was registered.");

              fusedLocationProviderClient.removeLocationUpdates(locationCallback);
              if (errorCallback != null) {
                errorCallback.onError(ErrorCodes.errorWhileAcquiringPosition);
              }
              return;
            }

            Location location = locationResult.getLastLocation();
            nmeaClient.enrichExtrasWithNmea(location);
            positionChangedCallback.onPositionChanged(location);
              log("postion successful delivered");
          }

          @Override
          public synchronized void onLocationAvailability(
              @NonNull LocationAvailability locationAvailability) {
            if (!locationAvailability.isLocationAvailable() && !checkLocationService(context)) {
              if (errorCallback != null) {
                errorCallback.onError(ErrorCodes.locationServicesDisabled);
              }
            }
          }
        };
  }

  private static LocationRequest buildLocationRequest(@Nullable LocationOptions options) {
    LocationRequest locationRequest = LocationRequest.create();

    if (options != null) {
      locationRequest.setPriority(toPriority(options.getAccuracy()));
      locationRequest.setInterval(options.getTimeInterval());
      locationRequest.setFastestInterval(options.getTimeInterval() / 2);
      locationRequest.setSmallestDisplacement(options.getDistanceFilter());
    }

    return locationRequest;
  }

  private static LocationSettingsRequest buildLocationSettingsRequest(
      LocationRequest locationRequest) {
    LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
    builder.addLocationRequest(locationRequest);

    return builder.build();
  }

  private static int toPriority(LocationAccuracy locationAccuracy) {
    switch (locationAccuracy) {
      case lowest:
        return Priority.PRIORITY_PASSIVE;
      case low:
        return Priority.PRIORITY_LOW_POWER;
      case medium:
        return Priority.PRIORITY_BALANCED_POWER_ACCURACY;
      default:
        return Priority.PRIORITY_HIGH_ACCURACY;
    }
  }

  private synchronized int generateActivityRequestCode() {
    SecureRandom random = new SecureRandom();
    return random.nextInt(1 << 16);
  }

  @SuppressLint("MissingPermission")
  private void requestPositionUpdates(LocationOptions locationOptions) {
    LocationRequest locationRequest = buildLocationRequest(locationOptions);
    this.nmeaClient.start();
    fusedLocationProviderClient.requestLocationUpdates(
        locationRequest, locationCallback, Looper.getMainLooper());
  }

  @Override
  public void isLocationServiceEnabled(LocationServiceListener listener) {
    LocationServices.getSettingsClient(context)
        .checkLocationSettings(new LocationSettingsRequest.Builder().build())
        .addOnCompleteListener(
            (response) -> {
              if (!response.isSuccessful()) {
                listener.onLocationServiceError(ErrorCodes.locationServicesDisabled);
              }

              LocationSettingsResponse lsr = response.getResult();
              if (lsr != null) {
                LocationSettingsStates settingsStates = lsr.getLocationSettingsStates();
                boolean isGpsUsable = settingsStates != null && settingsStates.isGpsUsable();
                boolean isNetworkUsable =
                    settingsStates != null && settingsStates.isNetworkLocationUsable();
                listener.onLocationServiceResult(isGpsUsable || isNetworkUsable);
              } else {
                listener.onLocationServiceError(ErrorCodes.locationServicesDisabled);
              }
            });
  }

  @SuppressLint("MissingPermission")
  @Override
  public void getLastKnownPosition(
      PositionChangedCallback positionChangedCallback, ErrorCallback errorCallback) {

    fusedLocationProviderClient
        .getLastLocation()
        .addOnSuccessListener(positionChangedCallback::onPositionChanged)
        .addOnFailureListener(
            e -> {
              Log.e("Geolocator", "Error trying to get last the last known GPS location");
              if (errorCallback != null) {
                errorCallback.onError(ErrorCodes.errorWhileAcquiringPosition);
              }
            });
  }

  public boolean onActivityResult(int requestCode, int resultCode) {
    if (requestCode == activityRequestCode) {
      if (resultCode == Activity.RESULT_OK) {
        if (this.locationOptions == null
            || this.positionChangedCallback == null
            || this.errorCallback == null) {
          return false;
        }

        requestPositionUpdates(this.locationOptions);

        return true;
      } else {
        if (errorCallback != null) {
          errorCallback.onError(ErrorCodes.locationServicesDisabled);
        }
      }
    }

    return false;
  }

  @SuppressLint("MissingPermission")
  public void startPositionUpdates(
      @Nullable Activity activity,
      @NonNull PositionChangedCallback positionChangedCallback,
      @NonNull ErrorCallback errorCallback) {

      checker = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> new Handler(Looper.getMainLooper()).post(() -> {
          logListener.onLog("FusedLocationClient", "Exception: Checking data...");
          fusedLocationProviderClient.getLocationAvailability().addOnSuccessListener((r) -> {
              logListener.onLog("FusedLocationClient", "availbility: " + r);
          });
          fusedLocationProviderClient.getLastLocation().addOnSuccessListener((r) -> {
              if(r == null) return;

              String locationMessage = r.getLongitude() +
                      " " +
                      r.getLatitude() +
                      " " +
                      r.getAccuracy() +
                      " " + r.getTime();


              logListener.onLog("FusedLocationClient", "lastLocation: " +locationMessage);
          });
      }),0,30, TimeUnit.SECONDS);

    this.positionChangedCallback = positionChangedCallback;
    this.errorCallback = errorCallback;

    LocationRequest locationRequest = buildLocationRequest(this.locationOptions);
    LocationSettingsRequest settingsRequest = buildLocationSettingsRequest(locationRequest);

    SettingsClient settingsClient = LocationServices.getSettingsClient(context);
    settingsClient
        .checkLocationSettings(settingsRequest)
        .addOnSuccessListener(
            locationSettingsResponse -> requestPositionUpdates(this.locationOptions))
        .addOnFailureListener(
            e -> {
                logListener.onLog("FusedLocationClient", "Exception: " + e + " " + e.getMessage());
              if (e instanceof ResolvableApiException) {
                // When we don't have an activity return an error code explaining the
                // location services are not enabled
                if (activity == null) {
                  errorCallback.onError(ErrorCodes.locationServicesDisabled);
                  return;
                }

                ResolvableApiException rae = (ResolvableApiException) e;
                int statusCode = rae.getStatusCode();
                if (statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                  try {
                    // Show the dialog by calling startResolutionForResult(), and check the
                    // result in onActivityResult().
                    rae.startResolutionForResult(activity, activityRequestCode);
                  } catch (IntentSender.SendIntentException sie) {
                    errorCallback.onError(ErrorCodes.locationServicesDisabled);
                  }
                } else {
                  errorCallback.onError(ErrorCodes.locationServicesDisabled);
                }
              } else {
                ApiException ae = (ApiException) e;
                int statusCode = ae.getStatusCode();
                if (statusCode == LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE) {
                  requestPositionUpdates(this.locationOptions);
                } else {
                  // This should not happen according to Android documentation but it has been
                  // observed on some phones.
                  errorCallback.onError(ErrorCodes.locationServicesDisabled);
                }
              }
            });
  }

  public void stopPositionUpdates() {
      if(checker != null) {
          checker.cancel(true);
      }
    this.nmeaClient.stop();
    fusedLocationProviderClient.removeLocationUpdates(locationCallback);
  }

    private void log(String message) {
        Log.e(TAG, message);
        if (logListener == null) return;
        this.logListener.onLog(TAG, message);
    }
}
