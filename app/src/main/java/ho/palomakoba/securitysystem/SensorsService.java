package ho.palomakoba.securitysystem;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

public class SensorsService extends Service implements SensorEventListener {
    private final String TAG = "SecuritySystem";
    private final static int SECONDS_TO_CHECK_SENSOR_VALUES = 10;

    private SensorManager mSensorManager = null;
    private Sensor accelerometerSensor;
    private Sensor lightSensor;
    private boolean hasLight = true;

    private LocalTime previousAccelerometerSensorEventTime = LocalTime.now().minus(
            Duration.ofSeconds(SECONDS_TO_CHECK_SENSOR_VALUES)
    );

    public SensorsService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");

        registerSensors();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, createNotification());
        return Service.START_STICKY;
    }

    private void registerSensors() {
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);


        accelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, accelerometerSensor, 3000000, 3000000);

        lightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mSensorManager.registerListener(this, lightSensor, 3000000, 3000000);

        Log.i(TAG, "Sensors registered");
    }

    private void unregisterSensors() {
        mSensorManager.unregisterListener(this, accelerometerSensor);
        mSensorManager.unregisterListener(this, lightSensor);

        Log.i(TAG, "Sensors unregistered");
    }

    @Override
    public void onDestroy() {
        unregisterSensors();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private boolean IsLocked() {
        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null) {
            return keyguardManager.isKeyguardLocked();
        }

        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        LocalTime currentAccelerometerSensorEventTime = LocalTime.now();
        long differenceInSeconds = ChronoUnit.SECONDS.between(previousAccelerometerSensorEventTime,
                currentAccelerometerSensorEventTime);

        int type = event.sensor.getType();

        if (differenceInSeconds >= SECONDS_TO_CHECK_SENSOR_VALUES) {

            if (type == Sensor.TYPE_ACCELEROMETER) {
                if (event.values[1] > 4 && IsLocked() && hasLight) {
                    Log.i(TAG, valuesToString(event.values));
                    previousAccelerometerSensorEventTime = currentAccelerometerSensorEventTime;

                    Intent takePictureIntent
                            = new Intent(getApplicationContext(), CameraActivity.class);
                    takePictureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    startActivity(takePictureIntent);
                    Log.i(TAG, "started camera activity");

                }
            }

            if (type == Sensor.TYPE_LIGHT) {
                hasLight = !(event.values[0] <= 10);
            }
        }

    }

    private String valuesToString(float[] values) {
        DecimalFormat df = new DecimalFormat("#");

        return "X: " + df.format(values[0]) +
                " Y: " +
                df.format(values[1]) +
                " Z: " +
                df.format(values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private Notification createNotification() {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel notificationChannel = new
                NotificationChannel("ho.palomakoba.securitysystem",
                "Security Service", NotificationManager.IMPORTANCE_NONE);
        notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        mNotificationManager.createNotificationChannel(notificationChannel);

        Intent intent = new Intent(SensorsService.this, SensorsService.class);
        PendingIntent pendingIntent = PendingIntent
                .getActivity(SensorsService.this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, notificationChannel.getId())
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("Security System")
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setContentIntent(pendingIntent);

        return notificationBuilder.build();
    }
    // https://github.com/hzitoun/android-camera2-secret-picture-taker
}