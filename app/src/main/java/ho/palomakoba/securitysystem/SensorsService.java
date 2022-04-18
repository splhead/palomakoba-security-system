package ho.palomakoba.securitysystem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SensorsService extends Service implements SensorEventListener {
    private final String TAG = "SecuritySystemService";
    private final int FRONT_CAMERA = 1;
    private SensorManager mSensorManager = null;
    private Sensor accelerometerSensor;
    private Sensor lightSensor;
    private Sensor motionSensor;
    private int counterTakePhoto = 0;
    private Camera camera = null;

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
                                takePhoto();
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

    private void takePhoto() {

        if (!safeCameraOpen()) {
            Log.e(TAG, "Could not get camera instance");
        } else {

            camera.takePicture(null, null, new Camera.PictureCallback() {

                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    File imageFileDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    if (!imageFileDir.exists() && !imageFileDir.mkdirs()) {
                        return;
                    }
                    String timeStamp =
                            new SimpleDateFormat("yyyyMMdd_HHmmss")
                                    .format(new Date());

                    String imageFileName = "ss_" + timeStamp + ".jpg";
                    String imageFullPath = imageFileDir.getPath() + File.separator + imageFileName;
                    File image = new File(imageFullPath);

                    try {
                        FileOutputStream fos = new FileOutputStream(image);
                        fos.write(data);
                        fos.close();
                        Log.i(TAG, "image saved");
                    } catch (Exception error) {
                        Log.e(TAG, "Image could not be saved");
                    }

                    if (camera != null)
                        camera.release();
                }
            });
        }


        counterTakePhoto++;


    }

    private boolean safeCameraOpen() {
        boolean qOpened = false;

        try {
            releaseCameraAndPreview();
            camera = Camera.open(FRONT_CAMERA);
            camera.setPreviewTexture(new SurfaceTexture(0));
            camera.startPreview();
            qOpened = (camera != null);
        } catch (Exception e) {
            Log.e(TAG, "failed to open camera");
            e.printStackTrace();
        }

        return qOpened;
    }

    private void releaseCameraAndPreview() {
        if (camera != null) {
            camera.release();
            camera = null;
        }
    }
}