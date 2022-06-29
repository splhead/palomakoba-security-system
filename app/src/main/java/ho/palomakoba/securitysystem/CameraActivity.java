package ho.palomakoba.securitysystem;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends Activity {
    private static final String TAG = "SecuritySystem";
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;

    private Integer sensorOrientation;

    private CameraDevice mCameraDevice;

    private  File imageFile;

    private String mCameraId;
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private String mImageFileName;
    private File mImageFolder;

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            mBackgroundHandler.post(new ImageSaver(imageReader.acquireLatestImage()));

            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);

            Uri uri = Uri.fromFile(imageFile);
            mediaScanIntent.setData(uri);
            sendBroadcast(mediaScanIntent);

            closeCamera();
        }
    };
    private ImageReader mImageReader;


    private CameraCaptureSession mCameraCaptureSession;

    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            Log.i(TAG, "Camera opened");
            mCameraOpenCloseLock.release();
            createCameraCaptureSession();

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    private void createCameraCaptureSession() {
        List<Surface> outputSurfaces = new ArrayList<>();
        outputSurfaces.add(mImageReader.getSurface());

        try {
            mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mCameraCaptureSession = cameraCaptureSession;
                    createCameraCaptureRequest();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraCaptureRequest() {
        try {
            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
            );
            captureRequestBuilder.addTarget(mImageReader.getSurface());

            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);


            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);

            mCameraCaptureSession.capture(captureRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);
                            try {
                                createImageFileName();
                                Log.i(TAG, "File created");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                            super.onCaptureCompleted(session, request, result);
                            Log.i(TAG, "Picture captured");
                        }
                    }
                    , mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;

    private void startBackgroundThread() {
        Log.i(TAG, "start bg thread");
        mBackgroundHandlerThread = new HandlerThread("SecuritySystem");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
            Log.i(TAG, "stop bg thread");
            new Handler().postDelayed(this::finish, 2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createImageFolder();

        // must be before setupCamera();
        // to avoid disable camera by policy
        handleKeyguard();

        setupCamera();

        Log.i(TAG, "onCreate");


    }

    private void handleKeyguard() {
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null)
            keyguardManager.requestDismissKeyguard(this, null);

    }

    @Override
    protected void onPause() {

        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Application will not run without camera services");
            }
        }
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Permission successfully granted!");
                setupCamera();
            } else {
                Log.i(TAG, "App needs to save video to run");
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }

    private static Size chooseOptimalSize(Size[] choices) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * 768 / 1080 &&
                    option.getWidth() >= 1080 && option.getHeight() >= 768) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return fixOrientation(Collections.min(bigEnough, new CompareSizeByArea()));
        } else {
            return fixOrientation(choices[0]);
        }
    }

    private static Size fixOrientation(Size choice) {
        if (choice.getWidth() > choice.getHeight()) {
            return new Size(choice.getHeight(), choice.getWidth());
        }
        return choice;
    }

    private void setupCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            //for (String cameraId : cameraManager.getCameraIdList()) {
            String cameraId = "1";
            CameraCharacteristics cameraCharacteristics
                    = cameraManager.getCameraCharacteristics(cameraId);

            sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            StreamConfigurationMap map =
                    cameraCharacteristics
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG)
            );

            //int orientation = getResources().getConfiguration().orientation;

            int width = mImageSize.getWidth();
            int height = mImageSize.getHeight();

            mImageReader = ImageReader.newInstance(width,
                    height, ImageFormat.JPEG, 1);

            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener,
                    mBackgroundHandler);
            mCameraId = cameraId;
            connectCamera();
            //}
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
                    throw  new RuntimeException("Timeout waitin to lock camera opening.");
                cameraManager.openCamera(mCameraId,
                        mCameraDeviceStateCallback, mBackgroundHandler);
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    Log.i(TAG, "Precisa de acesso a camera para funcionar");
                }
                requestPermissions(new String[]{Manifest.permission.CAMERA},
                        REQUEST_CAMERA_PERMISSION_RESULT);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Erro ao abrir a camera");
            e.printStackTrace();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while trying to lock camera opening.");
        }
    }

    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mCameraCaptureSession) {
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
        try {
            mCameraOpenCloseLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            mCameraOpenCloseLock.release();
        }
        Log.i(TAG, "Camera closed");
        new Handler().postDelayed(this::finish, 1500);
    }

    private File createImageFileName() throws IOException {
        String timestamp = new SimpleDateFormat("ddMMyyyy_HHmmss", Locale.US)
                .format(new Date());
        String prepend = "front_" + timestamp + "_";
         imageFile = File.createTempFile(prepend, ".jpg", mImageFolder);
        mImageFileName = imageFile.getAbsolutePath();
        return imageFile;
    }

    private void createImageFolder() {
        if (!hasWritePermission()) {
            return;
        }
        File imageFile = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mImageFolder = new File(imageFile, "security");
        if (!mImageFolder.exists()) {
            boolean mkdirs = mImageFolder.mkdirs();
            if (mkdirs) {
                Log.i(TAG, "Folder created");
            }
        }
    }

    private boolean hasWritePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Log.i(TAG, "necessário para salvar as fotos");
            }
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
        }
        return false;
    }

    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() /
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private class ImageSaver implements Runnable {

        private final Image mImage;

        public ImageSaver(Image mImage) {
            this.mImage = mImage;
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            FileOutputStream fileOutputStream = null;
            try {

                fileOutputStream = new FileOutputStream(mImageFileName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}