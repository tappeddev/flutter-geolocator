package com.baseflow.geolocator.location;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baseflow.geolocator.LogListener;
import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class LocationManagerClient implements LocationClient, LocationListener {

  private static final long TWO_MINUTES = 120000;

  private static final String TAG = "LocationManagerClient";
  private final LocationManager locationManager;
  private final NmeaClient nmeaClient;
  @Nullable private final LocationOptions locationOptions;
  public Context context;
  private boolean isListening = false;

  @Nullable private Location currentBestLocation;
  @Nullable private String currentLocationProvider;
  @Nullable private PositionChangedCallback positionChangedCallback;
  @Nullable private ErrorCallback errorCallback;

  private LogListener logListener;
  private ScheduledFuture<?> checker;

    public LocationManagerClient(
      @NonNull Context context, @Nullable LocationOptions locationOptions, @NonNull LogListener logListener) {
      this.logListener = logListener;
    this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    this.locationOptions = locationOptions;
    this.context = context;
    this.nmeaClient = new NmeaClient(context, locationOptions);
  }

  static boolean isBetterLocation(Location location, Location bestLocation, LogListener logListener) {
    if (bestLocation == null) return true;

    long timeDelta = location.getTime() - bestLocation.getTime();
    boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
    boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
    boolean isNewer = timeDelta > 0;

    if (isSignificantlyNewer) return true;

    if (isSignificantlyOlder) {
        logListener.onLog(TAG, "isBetterLocation: false -> isSignificantlyOlder (timeDelta: " + timeDelta + ")");
        return false;
    }

    float accuracyDelta = (int) (location.getAccuracy() - bestLocation.getAccuracy());
    boolean isLessAccurate = accuracyDelta > 0;
    boolean isMoreAccurate = accuracyDelta < 0;
    boolean isSignificantlyLessAccurate = accuracyDelta > 200;

    boolean isFromSameProvider = false;
    if (location.getProvider() != null) {
      isFromSameProvider = location.getProvider().equals(bestLocation.getProvider());
    }

    if (isMoreAccurate) return true;

    if (isNewer && !isLessAccurate) return true;

    //noinspection RedundantIfStatement
    if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return true;

    String message = "isBetterLocation: false\n"
            + "timeDelta: " + timeDelta + "\n"
            + "isSignificantlyNewer: " + isSignificantlyNewer + "\n"
            + "isSignificantlyOlder: " + isSignificantlyOlder + "\n"
            + "isNewer: " + isNewer + "\n"
            + "isLessAccurate" + isLessAccurate + "\n"
            + "isMoreAccurate" + isMoreAccurate + "\n"
            + "isSignificantlyLessAccurate" + isSignificantlyLessAccurate + "\n";

      logListener.onLog(TAG, message);
    return false;
  }

  private static String getBestProvider(
      LocationManager locationManager, LocationAccuracy accuracy) {
    Criteria criteria = new Criteria();

    criteria.setBearingRequired(false);
    criteria.setAltitudeRequired(false);
    criteria.setSpeedRequired(false);

    switch (accuracy) {
      case lowest:
        criteria.setAccuracy(Criteria.NO_REQUIREMENT);
        criteria.setHorizontalAccuracy(Criteria.NO_REQUIREMENT);
        criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
        break;
      case low:
        criteria.setAccuracy(Criteria.ACCURACY_COARSE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_LOW);
        criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
        break;
      case medium:
        criteria.setAccuracy(Criteria.ACCURACY_COARSE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_MEDIUM);
        criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
        break;
      default:
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
        criteria.setPowerRequirement(Criteria.POWER_HIGH);
        break;
    }

    String provider = locationManager.getBestProvider(criteria, true);

    if (provider.trim().isEmpty()) {
      List<String> providers = locationManager.getProviders(true);
      if (providers.size() > 0) provider = providers.get(0);
    }

    return provider;
  }

  private static float accuracyToFloat(LocationAccuracy accuracy) {
    switch (accuracy) {
      case lowest:
      case low:
        return 500;
      case medium:
        return 250;
      case best:
      case bestForNavigation:
        return 50;
      default:
        return 100;
    }
  }

  @Override
  public void isLocationServiceEnabled(LocationServiceListener listener) {
    if (locationManager == null) {
      listener.onLocationServiceResult(false);
      return;
    }

    listener.onLocationServiceResult(checkLocationService(context));
  }

  @Override
  public void getLastKnownPosition(
      PositionChangedCallback positionChangedCallback, ErrorCallback errorCallback) {
    Location bestLocation = null;

    for (String provider : locationManager.getProviders(true)) {
      @SuppressLint("MissingPermission")
      Location location = locationManager.getLastKnownLocation(provider);

      if (location != null && isBetterLocation(location, bestLocation, logListener)) {
        bestLocation = location;
      }
    }

    positionChangedCallback.onPositionChanged(bestLocation);
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode) {
    return false;
  }

  @SuppressLint("MissingPermission")
  @Override
  public void startPositionUpdates(
      Activity activity,
      PositionChangedCallback positionChangedCallback,
      ErrorCallback errorCallback) {

    if (!checkLocationService(context)) {
      errorCallback.onError(ErrorCodes.locationServicesDisabled);
      return;
    }

    this.positionChangedCallback = positionChangedCallback;
    this.errorCallback = errorCallback;

    LocationAccuracy locationAccuracy =
        this.locationOptions != null ? this.locationOptions.getAccuracy() : LocationAccuracy.best;

    this.currentLocationProvider = getBestProvider(this.locationManager, locationAccuracy);

    if (this.currentLocationProvider.trim().isEmpty()) {
      errorCallback.onError(ErrorCodes.locationServicesDisabled);
      return;
    }

    long timeInterval = 0;
    float distanceFilter = 0;
    if (this.locationOptions != null) {
      timeInterval = locationOptions.getTimeInterval();
      distanceFilter = locationOptions.getDistanceFilter();
    }

    this.isListening = true;
    this.nmeaClient.start();
    logListener.onLog(TAG, "Start position updates with provider: " + this.currentLocationProvider);
    this.locationManager.requestLocationUpdates(
        this.currentLocationProvider, timeInterval, distanceFilter, this, Looper.getMainLooper());

      checker = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> new Handler(Looper.getMainLooper()).post(() -> {
          logListener.onLog(TAG, "Checking again.");
          if(currentBestLocation != null) {
              logListener.onLog(TAG, "currentBestLocation last time: " + currentBestLocation.getTime());
          } else {
              logListener.onLog(TAG, "No currentBestLocation.");
          }
      }),0,30, TimeUnit.SECONDS);
  }

  @SuppressLint("MissingPermission")
  @Override
  public void stopPositionUpdates() {
    this.isListening = false;
    this.nmeaClient.stop();
    this.locationManager.removeUpdates(this);
    if(checker != null) {
        checker.cancel(true);
    }
  }

  @Override
  public synchronized void onLocationChanged(Location location) {
      logListener.onLog(TAG, "onLocationChanged");
    float desiredAccuracy =
        locationOptions != null ? accuracyToFloat(locationOptions.getAccuracy()) : 50;

    if (isBetterLocation(location, currentBestLocation, logListener)
        && location.getAccuracy() <= desiredAccuracy) {
      this.currentBestLocation = location;

      if (this.positionChangedCallback != null) {
        nmeaClient.enrichExtrasWithNmea(location);
        this.positionChangedCallback.onPositionChanged(currentBestLocation);
          logListener.onLog(TAG, "position delivered.");
      }
    } else {
        logListener.onLog(TAG, "position not better or not accurate enough. Accuracy: " + location.getAccuracy() + " provider: " + location.getProvider());
    }
  }

  @TargetApi(28)
  @SuppressWarnings("deprecation")
  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {
    if (status == android.location.LocationProvider.AVAILABLE) {
      onProviderEnabled(provider);
    } else if (status == android.location.LocationProvider.OUT_OF_SERVICE) {
      onProviderDisabled(provider);
    }
  }

  @Override
  public void onProviderEnabled(String provider) {}

  @SuppressLint("MissingPermission")
  @Override
  public void onProviderDisabled(String provider) {
      logListener.onLog(TAG, "onProviderDisabled: " + provider);
    if (provider.equals(this.currentLocationProvider)) {
      if (isListening) {
          logListener.onLog(TAG, "onProviderDisabled and removing updates: " + provider);
        this.locationManager.removeUpdates(this);
      }

      if (this.errorCallback != null) {
        errorCallback.onError(ErrorCodes.locationServicesDisabled);
      }

      this.currentLocationProvider = null;
    }
  }
}
