package com.example.android.sunshine.app;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by pavel on 7/3/16.
 */
public class WeatherListenerPhoneService extends WearableListenerService {
    GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d("Phone", "Message received");
        Intent wearableUpdaterIntent = new Intent(this, WearableUpdaterService.class);
        wearableUpdaterIntent.putExtra("force", true);
        startService(wearableUpdaterIntent);
    }
}
