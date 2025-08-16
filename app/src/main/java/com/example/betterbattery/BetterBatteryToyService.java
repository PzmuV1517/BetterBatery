package com.example.betterbattery;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

// Correct Glyph Matrix SDK imports based on the documentation
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphToy;
import com.nothing.ketchum.GlyphMatrixManager;
import com.nothing.ketchum.GlyphMatrixFrame;
import com.nothing.ketchum.GlyphMatrixObject;
import com.nothing.ketchum.GlyphMatrixUtils;

public class BetterBatteryToyService extends Service implements SensorEventListener {

    private static final int MATRIX_SIZE = 25;
    private static final int UPDATE_INTERVAL = 50; // 50ms for smoother animation

    private GlyphMatrixManager mGM;
    private GlyphMatrixManager.Callback mCallback;
    private Handler updateHandler;
    private Runnable updateRunnable;

    // Sensor management
    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Battery monitoring
    private BroadcastReceiver batteryReceiver;
    private int currentBatteryLevel = 50;

    // Enhanced liquid simulation variables
    private float tiltX = 0f;
    private float tiltY = 0f;
    private float[] liquidHeights = new float[MATRIX_SIZE];
    private float[] liquidVelocities = new float[MATRIX_SIZE]; // Add velocity for physics
    private float targetWaterLevel = 0.5f;
    private float gravityX = 0f; // Gravity in X direction (left/right)
    private float gravityY = 9.8f; // Gravity in Y direction (up/down)

    // Animation variables for startup effect
    private boolean isAnimating = false;
    private long animationStartTime = 0;
    private static final long ANIMATION_DURATION = 600; // 0.6 seconds (faster animation)
    private float animatedWaterLevel = 0f;
    private int animatedBatteryLevel = 0;

    @Override
    public IBinder onBind(Intent intent) {
        init();
        return serviceMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return false;
    }

    private void init() {
        // Initialize Glyph Matrix Manager
        mGM = GlyphMatrixManager.getInstance(getApplicationContext());
        mCallback = new GlyphMatrixManager.Callback() {
            @Override
            public void onServiceConnected(ComponentName componentName) {
                mGM.register(Glyph.DEVICE_23112); // Phone 3 device ID
                startBatteryAndSensorMonitoring();
                startUpdateLoop();
                // Start animation when the glyph toy is first opened
                startAnimation();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
            }
        };
        mGM.init(mCallback);

        // Initialize liquid heights - start with water at bottom for normal upright position
        for (int i = 0; i < MATRIX_SIZE; i++) {
            liquidHeights[i] = MATRIX_SIZE - 5f; // Start near bottom
            liquidVelocities[i] = 0f; // Start with no velocity
        }
    }

    private void startBatteryAndSensorMonitoring() {
        // Battery monitoring
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level != -1 && scale != -1) {
                    currentBatteryLevel = (int) ((level / (float) scale) * 100);
                    targetWaterLevel = currentBatteryLevel / 100f;
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        // Sensor monitoring
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void startUpdateLoop() {
        updateHandler = new Handler(Looper.getMainLooper());
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDisplay();
                updateHandler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        updateHandler.post(updateRunnable);
    }

    private void updateDisplay() {
        try {
            // Update animation if running
            if (isAnimating) {
                updateAnimation();
            }

            // Create the three layers using GlyphMatrixObject.Builder
            GlyphMatrixObject.Builder liquidBuilder = new GlyphMatrixObject.Builder();
            GlyphMatrixObject liquidLayer = liquidBuilder
                    .setImageSource(createLiquidBitmap())
                    .setPosition(0, 0)
                    .setBrightness(180)
                    .build();

            GlyphMatrixObject.Builder contrastBuilder = new GlyphMatrixObject.Builder();
            GlyphMatrixObject contrastLayer = contrastBuilder
                    .setImageSource(createContrastBitmap())
                    .setPosition(0, 0)
                    .setBrightness(255)
                    .build();

            // Use animated battery level during animation, otherwise use current battery level
            int displayLevel = isAnimating ? animatedBatteryLevel : currentBatteryLevel;

            GlyphMatrixObject.Builder textBuilder = new GlyphMatrixObject.Builder();
            GlyphMatrixObject textLayer = textBuilder
                    .setText(String.format("%02d%%", displayLevel))
                    .setPosition(4, 9) // Centered position for 25x25 matrix
                    .setBrightness(255)
                    .build();

            // Build the frame with three layers
            GlyphMatrixFrame.Builder frameBuilder = new GlyphMatrixFrame.Builder();
            GlyphMatrixFrame frame = frameBuilder
                    .addLow(liquidLayer)      // Bottom layer - liquid simulation
                    .addMid(contrastLayer)    // Middle layer - black background for text
                    .addTop(textLayer)        // Top layer - battery percentage text
                    .build(getApplicationContext());

            // Display the frame using render() method with exception handling
            mGM.setMatrixFrame(frame.render());
        } catch (Exception e) {
            // Handle any exceptions during frame rendering
            e.printStackTrace();
        }
    }

    private Bitmap createLiquidBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(MATRIX_SIZE, MATRIX_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Update liquid simulation based on tilt and battery level
        updateLiquidSimulation();

        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        paint.setAntiAlias(true);

        // Draw liquid as a wave pattern within circular boundary
        float centerX = MATRIX_SIZE / 2f;
        float centerY = MATRIX_SIZE / 2f;
        float radius = MATRIX_SIZE / 2f - 1;

        // Create liquid shape based on wave heights
        for (int x = 0; x < MATRIX_SIZE; x++) {
            float liquidHeight = liquidHeights[x];

            for (int y = 0; y < MATRIX_SIZE; y++) {
                float distance = (float) Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));

                // Only draw within circular boundary and below liquid surface
                if (distance <= radius && y >= liquidHeight) {
                    canvas.drawPoint(x, y, paint);
                }
            }
        }

        return bitmap;
    }

    private Bitmap createContrastBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(MATRIX_SIZE, MATRIX_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setAntiAlias(true);

        // Create a rounded rectangle background for the text with 2px padding
        float centerX = MATRIX_SIZE / 2f;
        float centerY = MATRIX_SIZE / 2f;

        // Approximate text dimensions for percentage display
        float textWidth = 8f; // Approximate width for "XX%"
        float textHeight = 6f; // Approximate height

        RectF rect = new RectF(
            centerX - textWidth / 2 - 2,
            centerY - textHeight / 2 - 2,
            centerX + textWidth / 2 + 2,
            centerY + textHeight / 2 + 2
        );

        canvas.drawRoundRect(rect, 2, 2, paint);

        return bitmap;
    }

    private void updateLiquidSimulation() {
        // Simplified but working water simulation
        // Calculate base water level based on battery percentage
        float baseWaterLevel = MATRIX_SIZE - (targetWaterLevel * (MATRIX_SIZE - 8f)); // Leave 8px margin at top

        // Apply tilt effects for realistic water behavior
        for (int x = 0; x < MATRIX_SIZE; x++) {
            // Skip if outside circular boundary
            float centerX = MATRIX_SIZE / 2f;
            float distanceFromCenter = Math.abs(x - centerX);
            if (distanceFromCenter > MATRIX_SIZE / 2f - 1) {
                liquidHeights[x] = MATRIX_SIZE; // Outside circle, no water
                continue;
            }

            // Calculate tilt effect - water flows based on phone orientation
            float tiltEffect = 0f;

            // When phone is upright (tiltY ≈ 0), water should be at bottom
            // When phone is upside down (tiltY ≈ -9.8), water should flow to top
            // When tilted left/right (tiltX ≠ 0), water should flow to lower side

            // Vertical tilt (upside down detection)
            if (tiltY < -5f) { // Phone is upside down
                tiltEffect += (tiltY + 5f) * 2f; // Water flows to "new bottom" (top of screen)
            } else if (tiltY > 5f) { // Phone tilted forward significantly
                tiltEffect -= (tiltY - 5f) * 1f; // Water flows away from top
            }

            // Horizontal tilt (left/right) - FIXED: Inverted the direction
            // When tiltX is positive (phone tilted right), water should flow to the right side
            // When tiltX is negative (phone tilted left), water should flow to the left side
            float horizontalOffset = (x - centerX) * (-tiltX) * 0.5f; // Added negative sign to fix direction
            tiltEffect += horizontalOffset;

            // Add wave motion for visual appeal
            long time = System.currentTimeMillis();
            float waveOffset = (float) Math.sin((x * 0.4 + time * 0.004)) * 0.8f;

            // Calculate target height for this column
            float targetHeight = baseWaterLevel + tiltEffect + waveOffset;

            // Smooth interpolation to target
            liquidHeights[x] += (targetHeight - liquidHeights[x]) * 0.12f;

            // Clamp to valid range
            liquidHeights[x] = Math.max(0f, Math.min((float)MATRIX_SIZE, liquidHeights[x]));
        }
    }

    private void redistributeWater(float targetVolume) {
        // Calculate current volume
        float currentVolume = 0;
        int validColumns = 0;

        for (int x = 0; x < MATRIX_SIZE; x++) {
            float centerX = MATRIX_SIZE / 2f;
            float distanceFromCenter = Math.abs(x - centerX);
            if (distanceFromCenter <= MATRIX_SIZE / 2f - 1) {
                currentVolume += (MATRIX_SIZE - liquidHeights[x]);
                validColumns++;
            }
        }

        // Adjust water levels to maintain target volume
        if (validColumns > 0) {
            float volumeError = targetVolume - currentVolume;
            float adjustment = volumeError / validColumns;

            for (int x = 0; x < MATRIX_SIZE; x++) {
                float centerX = MATRIX_SIZE / 2f;
                float distanceFromCenter = Math.abs(x - centerX);
                if (distanceFromCenter <= MATRIX_SIZE / 2f - 1) {
                    liquidHeights[x] -= adjustment * 0.1f; // Gradual adjustment
                    liquidHeights[x] = Math.max(0, Math.min(MATRIX_SIZE, liquidHeights[x]));
                }
            }
        }
    }

    private float rollAngle = 0f;
    private float pitchAngle = 0f;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float ax = event.values[0]; // X: left(+)/right(-)
            float ay = event.values[1]; // Y: up(+)/down(-)
            float az = event.values[2]; // Z: out of screen (+ toward user)

            // Low-pass filter for legacy vars
            float alpha = 0.8f;
            tiltX = alpha * tiltX + (1 - alpha) * ax;
            tiltY = alpha * tiltY + (1 - alpha) * ay;

            // For upright phone (screen facing user, bottom toward earth):
            // When rotating clockwise/counterclockwise, we need to detect rotation around the Z-axis
            // - Clockwise rotation: ax becomes more negative (left side goes down)
            // - Counterclockwise rotation: ax becomes more positive (right side goes down)

            // Calculate roll angle properly for upright orientation
            // When phone is upright, roll should be based on how much the phone tilts left/right
            // Use atan2(ax, az) - this gives us the angle of tilt around the Z-axis
            float newRollAngle = (float) Math.atan2(ax, az);

            // Calculate pitch (forward/back tilt)
            float newPitchAngle = (float) Math.atan2(-ay, Math.sqrt(ax * ax + az * az));

            // Apply low-pass filter to smooth the angles
            rollAngle = alpha * rollAngle + (1 - alpha) * newRollAngle;
            pitchAngle = alpha * pitchAngle + (1 - alpha) * newPitchAngle;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed for this implementation
    }

    // Message handler for Glyph Button interactions
    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case GlyphToy.MSG_GLYPH_TOY: {
                    Bundle bundle = msg.getData();
                    String event = bundle.getString(GlyphToy.MSG_GLYPH_TOY_DATA);
                    if (GlyphToy.EVENT_CHANGE.equals(event)) {
                        // Long press detected - restart animation
                        startAnimation();
                    } else if (GlyphToy.EVENT_AOD.equals(event)) {
                        // Always-On Display update (every minute)
                        updateDisplay();
                    }
                    break;
                }
                default:
                    super.handleMessage(msg);
            }
        }
    };

    private final Messenger serviceMessenger = new Messenger(serviceHandler);

    private void cleanup() {
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }

        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was not registered
            }
        }

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (mGM != null) {
            mGM.unInit();
            mGM = null;
        }

        mCallback = null;
    }

    private void updateAnimation() {
        if (!isAnimating) return;

        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - animationStartTime;

        // Calculate linear progress (0.0 to 1.0)
        float progress = Math.min(1.0f, (float) elapsed / ANIMATION_DURATION);

        // Linear interpolation for battery level
        animatedBatteryLevel = (int) (progress * currentBatteryLevel);

        // Linear interpolation for water level
        animatedWaterLevel = progress * (currentBatteryLevel / 100f);
        targetWaterLevel = animatedWaterLevel;

        // Stop animation when complete
        if (progress >= 1.0f) {
            isAnimating = false;
            animatedBatteryLevel = currentBatteryLevel;
            targetWaterLevel = currentBatteryLevel / 100f;
        }
    }

    private void startAnimation() {
        isAnimating = true;
        animationStartTime = System.currentTimeMillis();
        animatedBatteryLevel = 0;
        animatedWaterLevel = 0f;

        // Reset liquid heights to start empty
        for (int i = 0; i < MATRIX_SIZE; i++) {
            liquidHeights[i] = MATRIX_SIZE; // Start with empty liquid (water at bottom = high Y value)
        }
    }
}
