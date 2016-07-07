package com.example.android.sunshine.app;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class WearableUpdaterService extends IntentService {

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    GoogleApiClient mGoogleApiClient;

    public WearableUpdaterService() {
        super("WearableUpdaterService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            Context context = this;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String prevLowTempKey = context.getString(R.string.pref_prev_low_temp);
            String prevHighTempKey = context.getString(R.string.pref_prev_high_temp);
            String prevArtKey = context.getString(R.string.pref_prev_art);
            String prevLowTemp = prefs.getString(prevLowTempKey, null);
            String prevHighTemp = prefs.getString(prevHighTempKey, null);
            String prevArt = prefs.getString(prevArtKey, null);

            String locationQuery = Utility.getPreferredLocation(context);
            Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());
            Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

            if (cursor.moveToFirst()) {
                final String curLowTemp = Utility.formatTemperature(context, cursor.getDouble(INDEX_MIN_TEMP));
                final String curHighTemp = Utility.formatTemperature(context, cursor.getDouble(INDEX_MAX_TEMP));
                int weatherId = cursor.getInt(INDEX_WEATHER_ID);
                final String curArt = Utility.getArtUrlForWeatherCondition(context, weatherId);
                int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);

                Bitmap icon;
                try {
                    icon = Glide.with(context)
                            .load(curArt)
                            .asBitmap()
                            .error(artResourceId)
                            .fitCenter()
                            .into(90, 90).get();
                } catch (InterruptedException | ExecutionException e) {
                    icon = BitmapFactory.decodeResource(getResources(), artResourceId);
                }
                final Bitmap finalIcon = icon;

                if (intent.getBooleanExtra("force", false) ||
                        !curLowTemp.equals(prevLowTemp) ||
                        !curHighTemp.equals(prevHighTemp) ||
                        !(curArt != null && curArt.equals(prevArt))) {
                    if (mGoogleApiClient != null) {
                        putData(curLowTemp, curHighTemp, finalIcon);
                    } else {
                        mGoogleApiClient = new GoogleApiClient.Builder(this)
                                .addApi(Wearable.API)
                                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                                    @Override
                                    public void onConnected(@Nullable Bundle bundle) {
                                        putData(curLowTemp, curHighTemp, finalIcon);
                                    }

                                    @Override
                                    public void onConnectionSuspended(int i) {

                                    }
                                })
                                .build();
                        mGoogleApiClient.connect();
                    }
                }

                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(prevLowTempKey, curLowTemp);
                editor.putString(prevHighTempKey, curHighTemp);
                editor.putString(prevArtKey, curArt);
                editor.commit();
            }

            cursor.close();
        }
    }

    private void putData(String lowTemp, String highTemp, Bitmap icon) {
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/weather-data");
        putDataMapRequest.getDataMap().putString("low-temperature", lowTemp);
        putDataMapRequest.getDataMap().putString("high-temperature", highTemp);
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        icon.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        Asset iconAsset = Asset.createFromBytes(byteStream.toByteArray());
        putDataMapRequest.getDataMap().putAsset("icon", iconAsset);
        // Make sure data is always updated so onDataChanged is fired on the receiving side
        putDataMapRequest.getDataMap().putString("random", UUID.randomUUID().toString());
        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request);
    }
}
