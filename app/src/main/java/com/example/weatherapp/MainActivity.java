package com.example.weatherapp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements LocationListener, DefaultLifecycleObserver {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final String API_KEY = "2c2b71ab0635e255b765911f472c22c2";
    private static final long MIN_TIME_BETWEEN_UPDATES = 60000;
    private static final float MIN_DISTANCE_CHANGE = 100;

    private TextView weatherInfoTextView;
    private LocationManager locationManager;
    private ExecutorService executorService;
    private OkHttpClient client;
    private boolean isLocationUpdatesActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Prevent window leaks
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        // Initialize views
        weatherInfoTextView = findViewById(R.id.weatherinfo);

        // Initialize services
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        executorService = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        // Check permissions
        checkLocationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupResources();
    }

    private void cleanupResources() {
        stopLocationUpdates();

        if (executorService != null && !executorService.isShutdown()) {
            try {
                executorService.shutdown();
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        } else {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (isLocationUpdatesActive) {
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_BETWEEN_UPDATES,
                    MIN_DISTANCE_CHANGE,
                    this,
                    Looper.getMainLooper()
            );
            isLocationUpdatesActive = true;

            // Try to get last known location
            Location lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastLocation != null) {
                fetchWeatherData(lastLocation.getLatitude(), lastLocation.getLongitude());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Error starting location updates", e);
            Toast.makeText(this, "Error getting location updates", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopLocationUpdates() {
        if (!isLocationUpdatesActive) {
            return;
        }

        try {
            locationManager.removeUpdates(this);
            isLocationUpdatesActive = false;
        } catch (SecurityException e) {
            Log.e(TAG, "Error stopping location updates", e);
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        fetchWeatherData(location.getLatitude(), location.getLongitude());
    }

    private void fetchWeatherData(double latitude, double longitude) {
        // Use WeakReference to prevent memory leaks
        WeakReference<MainActivity> activityReference = new WeakReference<>(this);

        String url = String.format("https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s",
                latitude, longitude, API_KEY);

        executorService.execute(() -> {
            MainActivity activity = activityReference.get();
            if (activity == null) return;

            try {
                Request request = new Request.Builder()
                        .url(url)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.body() != null) {
                        String result = response.body().string();
                        activity.runOnUiThread(() -> updateUI(result));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error fetching weather data", e);
                activity.runOnUiThread(() -> {
                    weatherInfoTextView.setText("Error fetching weather data");
                    Toast.makeText(activity, "Network error", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void updateUI(String jsonData) {
        try {
            JSONObject jsonObject = new JSONObject(jsonData);
            String weather = jsonObject.getJSONArray("weather")
                    .getJSONObject(0)
                    .getString("description");
            double temp = jsonObject.getJSONObject("main")
                    .getDouble("temp") - 273.15;
            String cityName = jsonObject.getString("name");

            String weatherText = String.format("Location: %s\nWeather: %s\nTemperature: %.1f°C",
                    cityName, weather, temp);
            weatherInfoTextView.setText(weatherText);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather data", e);
            weatherInfoTextView.setText("Error parsing weather data");
            Toast.makeText(this, "Error processing weather data", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                weatherInfoTextView.setText("Location permission required for weather updates");
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}