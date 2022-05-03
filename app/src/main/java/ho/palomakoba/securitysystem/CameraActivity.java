package ho.palomakoba.securitysystem;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import ho.palomakoba.securitysystem.databinding.ActivityCameraBinding;

public class CameraActivity extends Activity {
    private static final String TAG = "SecuritySystem";
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private int mCaptureState = STATE_PREVIEW;

    private ActivityCameraBinding binding;

    private TextureView mTextureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private CameraDevice mCameraDevice;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private String mCameraId;
    private Size mPreviewSize;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private String mImageFileName;
    private File mImageFolder;

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            mBackgroundHandler.post(new ImageSaver(imageReader.acquireLatestImage()));
        }
    };
    private int mTotalRotation;
    private Size mImageSize;
    private ImageReader mImageReader;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture,
                                              int width, int height) {
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture,
                                                int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };
    private CameraCaptureSession mCameraCaptureSession;
    private final CameraCaptureSession.CaptureCallback mCameraCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult captureResult) {
                    switch (mCaptureState) {
                        case STATE_PREVIEW:
                            break;
                        case STATE_WAIT_LOCK:
                            mCaptureState = STATE_PREVIEW;
                            /*Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {*/

                            startStillCaptureRequest();

                            //}
                            break;
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    process(result);
                }
            };
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            Log.i(TAG, "Camera opened");
            startPreview();
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    lockFocus();
                }
            }, 200);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height) {
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
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        createImageFolder();

        mTextureView = binding.textureView;

        mTextureView.setAlpha(0.05f);

        handleKeyguard();
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

        closeCamera();

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
                lockFocus();
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

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics
                        = cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                        == CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }
                StreamConfigurationMap map =
                        cameraCharacteristics
                                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = mTotalRotation == 90 || mTotalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class)
                        , rotatedWidth, rotatedHeight);
                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG),
                        rotatedWidth, rotatedHeight);
                mImageReader = ImageReader.newInstance(mImageSize.getWidth(),
                        mImageSize.getHeight(), ImageFormat.JPEG, 1);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener,
                        mBackgroundHandler);
                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            if (null == mCameraDevice) return;
            mCaptureRequestBuilder = mCameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface,
                    mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            try {
                                mCameraCaptureSession = cameraCaptureSession;
                                mCameraCaptureSession.setRepeatingRequest(
                                        mCaptureRequestBuilder.build(),
                                        null, mBackgroundHandler
                                );
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "unable to use camera");
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
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
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        Log.i(TAG, "Camera closed");
    }

    private void startStillCaptureRequest() {
        try {
            if (null == mCameraDevice) return;
            mCaptureRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            mCaptureRequestBuilder.addTarget(mImageReader.getSurface());

            CameraCaptureSession.CaptureCallback stillCaptureCallback =
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

                    };
            mCameraCaptureSession.capture(mCaptureRequestBuilder.build(),
                    stillCaptureCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private File createImageFileName() throws IOException {
        String timestamp = new SimpleDateFormat("ddMMyyyy_HHmmss").format(new Date());
        String prepend = "front_" + timestamp + "_";
        File imageFile = File.createTempFile(prepend, ".jpg", mImageFolder);
        mImageFileName = imageFile.getAbsolutePath();
        return imageFile;
    }

    private void createImageFolder() {
        File imageFile = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mImageFolder = new File(imageFile, "security");
        if (!mImageFolder.exists()) {
            mImageFolder.mkdirs();
        }
    }

    private void lockFocus() {
        if (!hasWritePermission()) {
            return;
        }
        mCaptureState = STATE_WAIT_LOCK;
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            if (null == mCameraCaptureSession) return;
            mCameraCaptureSession.capture(mCaptureRequestBuilder.build(),
                    mCameraCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
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