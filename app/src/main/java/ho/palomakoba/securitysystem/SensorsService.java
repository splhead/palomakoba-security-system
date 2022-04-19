package ho.palomakoba.securitysystem;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.text.DecimalFormat;

public class SensorsService extends Service implements SensorEventListener {
    private final String TAG = "SecuritySystemService";


    private SensorManager mSensorManager = null;
    private Sensor accelerometerSensor;
    private Sensor lightSensor;
    private Sensor motionSensor;
    private int counterTakePhoto = 0;
    private CameraDevice cameraDevice;
    private String cameraId;
    private CameraManager mCameraManager = null;

    CameraDevice.StateCallback stateCallBack = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            camera.close();
            cameraDevice = null;
        }
    };


    public SensorsService() {
    }


    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
        openCamera();
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

        motionSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT);
        mSensorManager.registerListener(this, motionSensor, 3000000, 3000000);
        Log.i(TAG, "Sensors registered");
    }

    private void unregisterSensors() {
        mSensorManager.unregisterListener(this, accelerometerSensor);
        mSensorManager.unregisterListener(this, lightSensor);
        mSensorManager.unregisterListener(this, motionSensor);
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

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
                if (event.values[1] > 4) {
                    Log.i(TAG, valuesToString(event.values));
                    if (counterTakePhoto <= 0) {
                        final Handler handler = new Handler(Looper.getMainLooper());
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                //takePhoto();
                            }
                        }, 5000);
                    }
                }
                break;
            case Sensor.TYPE_LIGHT:
                if (event.values[0] < 10) {
                    Log.i(TAG, "Light: " + event.values[0]);
                }
                break;
            case Sensor.TYPE_MOTION_DETECT:
                Log.i(TAG, "Motion: " + event.values[0]);
                break;
        }

    }

    private String valuesToString(float[] values) {
        DecimalFormat df = new DecimalFormat("#");
        return "X: " + df.format(values[0])
                + " Y: " + df.format(values[1]) + " Z: " + df.format(values[2]);
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
                .getActivity(SensorsService.this, 0, intent, 0);

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
    private void openCamera() {
        mCameraManager =
                (CameraManager) getSystemService(CAMERA_SERVICE);

        try {
            cameraId = mCameraManager.getCameraIdList()[1];
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                mCameraManager.openCamera(cameraId, stateCallBack, null);
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error while opening camera " + cameraId, e);
        }
    }


}