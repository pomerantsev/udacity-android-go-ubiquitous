/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface LIGHT_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Paint mIconPaint;
        boolean mAmbient;
        Time mTime;
        Date mDate;
        Rect mHighTempTextBounds = new Rect();
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mScreenWidth;
        float mTimeYOffset;
        float mDateYOffset;
        float mIconSize;
        float mWeatherCenterYOffset;
        float mWeatherSpaceWidth;
        float mLineYOffset;
        float mLineLength;
        GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);
            mDateYOffset = resources.getDimensionPixelOffset(R.dimen.date_y_offset);

            mLineYOffset = resources.getDimensionPixelOffset(R.dimen.line_y_offset);
            mLineLength = resources.getDimensionPixelSize(R.dimen.line_length);

            mWeatherCenterYOffset = resources.getDimension(R.dimen.weather_center_y_offset);
            mWeatherSpaceWidth = resources.getDimensionPixelOffset(R.dimen.weather_space_width);
            mIconSize = resources.getDimensionPixelSize(R.dimen.icon_size);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint.setColor(resources.getColor(R.color.primary_text));
            mTimePaint.setTypeface(LIGHT_TYPEFACE);
            mTimePaint.setTextAlign(Paint.Align.CENTER);
            mTimePaint.setAntiAlias(true);

            mDatePaint = new Paint();
            mDatePaint.setColor(resources.getColor(R.color.secondary_text));
            mDatePaint.setTypeface(NORMAL_TYPEFACE);
            mDatePaint.setTextAlign(Paint.Align.CENTER);
            mDatePaint.setAntiAlias(true);

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.line));
            mLinePaint.setStrokeWidth(resources.getDimensionPixelSize(R.dimen.line_thickness));
            mLinePaint.setAntiAlias(true);

            mHighTempPaint = new Paint();
            mHighTempPaint.setColor(resources.getColor(R.color.primary_text));
            mHighTempPaint.setTypeface(NORMAL_TYPEFACE);
            mHighTempPaint.setAntiAlias(true);

            mLowTempPaint = new Paint();
            mLowTempPaint.setColor(resources.getColor(R.color.secondary_text));
            mLowTempPaint.setTypeface(NORMAL_TYPEFACE);
            mLowTempPaint.setAntiAlias(true);

            mIconPaint = new Paint();
            mIconPaint.setAntiAlias(true);

            mTime = new Time();
            mDate = new Date();
        }

        private Collection<String> getNodes() {
            HashSet<String> results = new HashSet<>();
            NodeApi.GetConnectedNodesResult nodes =
                    Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

            for (Node node : nodes.getNodes()) {
                results.add(node.getId());
            }

            return results;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                if (!WeatherListenerService.sWeatherReceived) {
                    Log.d("WeatherListener", "Weather not received");
                    mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                            .addApi(Wearable.API)
                            .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                                @Override
                                public void onConnected(@Nullable Bundle bundle) {
                                    Log.d("WeatherListener", "Connected to google api");
                                    (new AsyncTask<Void, Void, Void>() {
                                        @Override
                                        protected Void doInBackground(Void... params) {
                                            Log.d("WeatherListener", "Starting the async task");
                                            Collection<String> nodes = getNodes();
                                            Log.d("WeatherListener", "Node count: " + nodes.size());
                                            for (String node : nodes) {
                                                Wearable.MessageApi.sendMessage(
                                                        mGoogleApiClient, node, "/request-data", new byte[0]).setResultCallback(
                                                        new ResultCallback<MessageApi.SendMessageResult>() {
                                                            @Override
                                                            public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                                                if (!sendMessageResult.getStatus().isSuccess()) {
                                                                    Log.e("WeatherListener", "Failed to send message with status code: "
                                                                            + sendMessageResult.getStatus().getStatusCode());
                                                                } else {
                                                                    Log.d("WeatherListener", "Message sent successfully");
                                                                }
                                                            }
                                                        }
                                                );
                                            }

                                            return null;
                                        }
                                    }).execute();
                                }

                                @Override
                                public void onConnectionSuspended(int i) {
                                    Log.d("WeatherListener", "Connection suspended");
                                }
                            })
                            .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                                @Override
                                public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                                    Log.d("WeatherListener", "Connection failed: " + connectionResult);
                                }
                            })
                            .build();
                    mGoogleApiClient.connect();
                }

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            mScreenWidth = resources.getDisplayMetrics().widthPixels;
            mTimePaint.setTextSize(resources.getDimensionPixelSize(R.dimen.time_text_size));
            mDatePaint.setTextSize(resources.getDimensionPixelSize(R.dimen.date_text_size));
            mHighTempPaint.setTextSize(resources.getDimensionPixelSize(R.dimen.temp_text_size));
            mLowTempPaint.setTextSize(resources.getDimensionPixelSize(R.dimen.temp_text_size));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mLinePaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
                    mIconPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
//            Log.d("WeatherListener", "Drawing");
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            mDate.setTime(mTime.toMillis(false));
            String timeText = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(timeText, mScreenWidth / 2, mTimeYOffset, mTimePaint);

            String dateText = new SimpleDateFormat("EEE, MMM d yyyy").format(mDate).toUpperCase();
            canvas.drawText(dateText, mScreenWidth / 2, mDateYOffset, mDatePaint);

            if (WeatherListenerService.sWeatherReceived) {
                canvas.drawLine((mScreenWidth - mLineLength) / 2, mLineYOffset,
                        (mScreenWidth + mLineLength) / 2, mLineYOffset, mLinePaint);
                mHighTempPaint.getTextBounds(WeatherListenerService.sHighTemperature, 0,
                        WeatherListenerService.sHighTemperature.length(), mHighTempTextBounds);
                float weatherTopY = mWeatherCenterYOffset - mHighTempTextBounds.exactCenterY();
                float highTempWidth = mHighTempTextBounds.width();
                float iconWidth = 0;
                float iconHeight = 0;
                if (WeatherListenerService.sIcon != null) {
                    iconWidth = WeatherListenerService.sIcon.getWidth();
                    iconHeight = WeatherListenerService.sIcon.getHeight();
                }
                float lowTempWidth = mLowTempPaint.measureText(WeatherListenerService.sLowTemperature);
                float fullWeatherWidth = iconWidth + highTempWidth + lowTempWidth + 2 * mWeatherSpaceWidth;
                if (WeatherListenerService.sIcon != null) {
                    canvas.drawBitmap(WeatherListenerService.sIcon, (mScreenWidth - fullWeatherWidth) / 2,
                            mWeatherCenterYOffset - iconHeight / 2,mIconPaint);
                }
                canvas.drawText(WeatherListenerService.sHighTemperature,
                        (mScreenWidth - fullWeatherWidth) / 2 + iconWidth + mWeatherSpaceWidth,
                        weatherTopY, mHighTempPaint);
                canvas.drawText(WeatherListenerService.sLowTemperature,
                        (mScreenWidth - fullWeatherWidth) / 2 + iconWidth + highTempWidth + 2 * mWeatherSpaceWidth,
                        weatherTopY, mLowTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
