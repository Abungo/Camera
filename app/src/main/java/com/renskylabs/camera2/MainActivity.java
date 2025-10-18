package com.renskylabs.camera2;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.renskylabs.camera2.databinding.ActivityMainBinding;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private static final String TAG = "ManualCamera2";
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    private ActivityMainBinding binding;

    // Camera 2 Core
    private String mCameraId;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCameraCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Size mPreviewSize;

    // Background
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    // Manual ranges and state
    private Range<Integer> mIsoRange;
    private Range<Long> mExposureRange;
    private Float mMinFocusDistance = 0.0f;
    private float mCurrentZoom = 1.0f;
    private float mMaxZoom = 1.0f;

    private boolean mIsIsoAuto = true;
    private boolean mIsShutterAuto = true;
    private boolean mIsFocusAuto = true;

    private ArcSliderView mFocusArcSlider;
    private ArcSliderView mIsoArcSlider;
    private ArcSliderView mShutterArcSlider;
    private ArcSliderView mZoomArcSlider;

    private ImageButton mFocusButton;
    private ImageButton mIsoButton;
    private ImageButton mShutterButton;
    private ImageButton mZoomButton;
    private ImageButton mCaptureButton;

    private int mMinIso = 100, mMaxIso = 1600;
    private long mMinExposure = 5_000_000L, mMaxExposure = 1_000_000_000L;
    private int mCurrentIso = mMinIso;
    private long mCurrentExposure = mMinExposure;

    // Still capture
    private ImageReader mImageReader;
    private Size mCaptureSize;
    private int mSensorOrientation = 0;
    private boolean mIsFrontFacing = false;

    // Capture state machine
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;
    private int mState = STATE_PREVIEW;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        AutoFitTextureView mTextureView = findViewById(R.id.texture);
        mTextureView.setAspectRatio(3, 4);

        // Sliders
        mFocusArcSlider = findViewById(R.id.focus_arc_slider);
        mFocusArcSlider.setOnSliderChangeListener(this::applyManualFocus);

        mIsoArcSlider = findViewById(R.id.iso_arc_slider);
        mIsoArcSlider.setOnSliderChangeListener(this::applyManualIso);

        mShutterArcSlider = findViewById(R.id.shutter_arc_slider);
        mShutterArcSlider.setOnSliderChangeListener(this::applyManualShutter);

        mZoomArcSlider = findViewById(R.id.zoom_arc_slider);
        mZoomArcSlider.setOnSliderChangeListener(this::applyManualZoom);

        // Buttons
        mFocusButton = findViewById(R.id.focus_button);
        mIsoButton = findViewById(R.id.iso_button);
bbudnemsljdndbx  dbxnxncnnc       mShutterButton = findViewById(R.id.shutter_button);
        mZoomButton = findViewById(R.id.zoom_button);
        mCaptureButton = findViewById(R.id.shutterButton);

        mFocusButton.setOnClickListener(this);
        mIsoButton.setOnClickListener(this);
        mShutterButton.setOnClickListener(this);
        mZoomButton.setOnClickListener(this);
        if (mCaptureButton != null) {
            mCaptureButton.setOnClickListener(v -> takePicture());
        }

        binding.texture.setSurfaceTextureListener(mSurfaceTextureListener);
    } // end onCreate

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (binding.texture.isAvailable()) {
            openCamera();
        } else {
            binding.texture.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    } // end onResume

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    } // end onPause

    // Toggle handlers (called from XML onClick or programmatically)
    public void toggleIsoAuto(View v) {
        mIsIsoAuto = !mIsIsoAuto;
        if (mIsIsoAuto && mIsoArcSlider.getVisibility() == View.VISIBLE) {
            toggleArcSlider(mIsoArcSlider);
        }
        binding.isoLabel.setText(
                mIsIsoAuto ? "ISO: Auto" : String.format(Locale.US, "ISO: %d", mCurrentIso));
        updatePreview();
    }

    public void toggleShutterAuto(View v) {
        mIsShutterAuto = !mIsShutterAuto;
        if (mIsShutterAuto && mShutterArcSlider.getVisibility() == View.VISIBLE) {
            toggleArcSlider(mShutterArcSlider);
        }
        String timeStr =
                (mCurrentExposure >= 1_000_000_000L)
                        ? String.format(Locale.US, "%.1fs", mCurrentExposure / 1.0e+09)
                        : String.format(
                                Locale.US, "1/%d", (int) (1_000_000_000L / mCurrentExposure));
        binding.shutterLabel.setText(
                mIsShutterAuto ? "Shutter: Auto" : String.format("Shutter: %s", timeStr));
        updatePreview();
    }

    public void toggleFocusAuto(View v) {
        mIsFocusAuto = !mIsFocusAuto;
        if (mIsFocusAuto && mFocusArcSlider.getVisibility() == View.VISIBLE) {
            toggleArcSlider(mFocusArcSlider);
        }
        String focusStr = mIsFocusAuto ? "Auto" : "Manual";
        binding.focusLabel.setText(String.format("Focus: %s", focusStr));
        updatePreview();
    }

    private void toggleArcSlider(ArcSliderView targetSlider) {
        ArcSliderView[] allSliders = {
            mFocusArcSlider, mIsoArcSlider, mShutterArcSlider, mZoomArcSlider
        };
        if (targetSlider.getVisibility() == View.VISIBLE) {
            targetSlider.setVisibility(View.GONE);
        } else {
            for (ArcSliderView slider : allSliders) {
                if (slider.getVisibility() == View.VISIBLE) {
                    slider.setVisibility(View.GONE);
                }
            }
            targetSlider.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.focus_button && mFocusArcSlider != null) {
            toggleArcSlider(mFocusArcSlider);
        } else if (id == R.id.iso_button && mIsoArcSlider != null) {
            toggleArcSlider(mIsoArcSlider);
        } else if (id == R.id.shutter_button && mShutterArcSlider != null) {
            toggleArcSlider(mShutterArcSlider);
        } else if (id == R.id.zoom_button && mZoomArcSlider != null) {
            toggleArcSlider(mZoomArcSlider);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (binding.texture.isAvailable()) {
                    binding.texture.post(this::openCamera);
                }
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while stopping background thread", e);
            }
        }
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        @NonNull SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        @NonNull SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            };

    private final CameraDevice.StateCallback mStateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    mCameraDevice = cameraDevice;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    cameraDevice.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int error) {
                    cameraDevice.close();
                    mCameraDevice = null;
                    Toast.makeText(MainActivity.this, "Camera Error: " + error, Toast.LENGTH_SHORT)
                            .show();
                }
            };

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        if (binding.texture.getWidth() == 0 || binding.texture.getHeight() == 0) return;

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            mCameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);

            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) mSensorOrientation = orientation;
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            mIsFrontFacing = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

            int viewWidth = binding.texture.getWidth();
            int viewHeight = binding.texture.getHeight();
            mPreviewSize =
                    chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture.class),
                            viewWidth,
                            viewHeight,
                            new Size(viewWidth, viewHeight));

            // Capture size (prefer largest 4:3)
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes != null && jpegSizes.length > 0) {
                List<Size> fourThree = new ArrayList<>();
                for (Size s : jpegSizes) {
                    double ratio = (double) s.getWidth() / s.getHeight();
                    if (Math.abs(ratio - 4.0 / 3.0) < 0.05) fourThree.add(s);
                }
                if (!fourThree.isEmpty()) {
                    mCaptureSize = Collections.max(fourThree, new CompareSizesByArea());
                } else {
                    mCaptureSize =
                            Collections.max(Arrays.asList(jpegSizes), new CompareSizesByArea());
                }
            } else {
                mCaptureSize = mPreviewSize;
            }

            setupImageReader();
            configureTransform(viewWidth, viewHeight);
            initializeControlRanges(characteristics);

            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access failed", e);
        }
    }

    private void closeCamera() {
        if (mCameraCaptureSession != null) {
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private Size chooseOptimalSize(
            Size[] choices, int textureViewWidth, int textureViewHeight, Size targetAspectRatio) {
        final double TARGET_RATIO = 4.0 / 3.0;
        List<Size> matching = new ArrayList<>();
        for (Size option : choices) {
            double ratio = (double) option.getWidth() / option.getHeight();
            if (Math.abs(ratio - TARGET_RATIO) < 0.05) matching.add(option);
        }
        if (matching.isEmpty()) {
            Log.w(TAG, "No 4:3 preview size found. Using largest available.");
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
        Size optimalSize = null;
        for (Size size : matching) {
            if (size.getWidth() >= textureViewWidth && size.getHeight() >= textureViewHeight) {
                if (optimalSize == null
                        || size.getWidth() * size.getHeight()
                                < optimalSize.getWidth() * optimalSize.getHeight()) {
                    optimalSize = size;
                }
            }
        }
        if (optimalSize == null) {
            optimalSize = Collections.max(matching, new CompareSizesByArea());
        }
        return optimalSize;
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (mPreviewSize == null || binding.texture == null) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER);
            float scale =
                    Math.max(
                            (float) viewHeight / mPreviewSize.getHeight(),
                            (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }
        binding.texture.setTransform(matrix);
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = binding.texture.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface imageSurface = (mImageReader != null) ? mImageReader.getSurface() : null;

            mPreviewRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(previewSurface);

            List<Surface> outputs = new ArrayList<>();
            outputs.add(previewSurface);
            if (imageSurface != null) outputs.add(imageSurface);

            mCameraDevice.createCaptureSession(
                    outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            mCameraCaptureSession = cameraCaptureSession;
                            if (mFocusArcSlider != null) mFocusArcSlider.setProgress(0.0f);
                            if (mIsoArcSlider != null) mIsoArcSlider.setProgress(0.0f);
                            if (mShutterArcSlider != null) mShutterArcSlider.setProgress(0.0f);
                            if (mZoomArcSlider != null) mZoomArcSlider.setProgress(0.0f);
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(
                                            MainActivity.this,
                                            "Configuration Failed",
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    },
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating session", e);
        }
    }

    private void updatePreview() {
        if (mCameraDevice == null
                || mCameraCaptureSession == null
                || mPreviewRequestBuilder == null) return;
        try {
            // AE/ISO/Exposure
            if (mIsIsoAuto && mIsShutterAuto) {
                mPreviewRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
                mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
            } else {
                mPreviewRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                if (!mIsIsoAuto) {
                    mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mCurrentIso);
                } else {
                    mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
                }
                if (!mIsShutterAuto) {
                    mPreviewRequestBuilder.set(
                            CaptureRequest.SENSOR_EXPOSURE_TIME, mCurrentExposure);
                } else {
                    mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
                }
            }

            // AF/Focus
            if (mIsFocusAuto) {
                mPreviewRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null);
            } else {
                mPreviewRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
            }

            // AWB
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            // Zoom/crop
            RectF cropRect = new RectF(0, 0, 1, 1);
            applyZoomToCrop(cropRect, mCurrentZoom);
            mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, getCropRect(cropRect));

            mCameraCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error starting preview", e);
        }
    }

    private void initializeControlRanges(CameraCharacteristics characteristics) {
        mIsoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (mIsoRange != null) {
            mMinIso = mIsoRange.getLower();
            mMaxIso = mIsoRange.getUpper();
        }
        mExposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (mExposureRange != null) {
            mMinExposure = mExposureRange.getLower();
            mMaxExposure = mExposureRange.getUpper();
        }
        Float minFocus =
                characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        mMinFocusDistance = (minFocus != null) ? minFocus : 0.0f;

        android.graphics.Rect activeArray =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray != null && mPreviewSize != null) {
            mMaxZoom =
                    Math.max((float) activeArray.width() / (float) mPreviewSize.getWidth(), 1.0f);
            mMaxZoom = Math.max(mMaxZoom, 15.0f);
        }
    }

    private void applyManualFocus(float progress) {
        if (mPreviewRequestBuilder == null
                || mCameraCaptureSession == null
                || mMinFocusDistance == null) return;
        mIsFocusAuto = false;
        float focusValue = progress * mMinFocusDistance;
        String focusStr =
                (focusValue <= 0.001f) ? "∞" : String.format(Locale.US, "%.2fm", 1.0f / focusValue);
        binding.focusLabel.setText(String.format("Focus: %s", focusStr));
        try {
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusValue);
            mCameraCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set manual focus: " + e.getMessage());
        }
    }

    private void applyManualIso(float progress) {
        if (mPreviewRequestBuilder == null || mCameraCaptureSession == null) return;
        mIsIsoAuto = false;
        mCurrentIso = (int) (mMinIso + (mMaxIso - mMinIso) * progress);
        binding.isoLabel.setText(String.format(Locale.US, "ISO: %d", mCurrentIso));
        try {
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mCurrentIso);
            if (!mIsShutterAuto) {
                mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mCurrentExposure);
            } else {
                mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
            }
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            mCameraCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set manual ISO: " + e.getMessage());
        }
    }

    private void applyManualShutter(float progress) {
        if (mPreviewRequestBuilder == null || mCameraCaptureSession == null) return;
        mIsShutterAuto = false;
        double logMin = Math.log(mMinExposure);
        double logMax = Math.log(mMaxExposure);
        double logValue = logMin + (logMax - logMin) * progress;
        mCurrentExposure = (long) Math.exp(logValue);
        String timeStr =
                (mCurrentExposure >= 1_000_000_000L)
                        ? String.format(Locale.US, "%.1fs", mCurrentExposure / 1.0e+09)
                        : String.format(
                                Locale.US, "1/%d", (int) (1_000_000_000L / mCurrentExposure));
        binding.shutterLabel.setText(String.format("Shutter: %s", timeStr));
        try {
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mCurrentExposure);
            if (!mIsIsoAuto) {
                mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mCurrentIso);
            } else {
                mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, null);
            }
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            mCameraCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set manual shutter: " + e.getMessage());
        }
    }

    private void applyManualZoom(float progress) {
        if (mPreviewRequestBuilder == null || mCameraCaptureSession == null) return;
        mCurrentZoom = 1.0f + (mMaxZoom - 1.0f) * progress;
        binding.focusLabel.setText(String.format("Zoom: %.1fx", mCurrentZoom));
        try {
            RectF cropRect = new RectF(0, 0, 1, 1);
            applyZoomToCrop(cropRect, mCurrentZoom);
            mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, getCropRect(cropRect));
            mCameraCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set zoom: " + e.getMessage());
        }
    }

    private void applyZoomToCrop(RectF cropRect, float zoom) {
        float normalizedZoom = Math.max(1.0f, Math.min(mMaxZoom, zoom));
        float ratio = 1.0f / normalizedZoom;
        float centerW = cropRect.width() / 2.0f;
        float centerH = cropRect.height() / 2.0f;
        cropRect.left = centerW - (ratio * centerW);
        cropRect.top = centerH - (ratio * centerH);
        cropRect.right = centerW + (ratio * centerW);
        cropRect.bottom = centerH + (ratio * centerH);
    }

    private android.graphics.Rect getCropRect(RectF normalizedRect) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
            android.graphics.Rect active =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null) return null;
            int left = (int) (active.left + normalizedRect.left * active.width());
            int top = (int) (active.top + normalizedRect.top * active.height());
            int right = (int) (active.left + normalizedRect.right * active.width());
            int bottom = (int) (active.top + normalizedRect.bottom * active.height());
            return new android.graphics.Rect(
                    clamp(left, active.left, active.right),
                    clamp(top, active.top, active.bottom),
                    clamp(right, active.left, active.right),
                    clamp(bottom, active.top, active.bottom));
        } catch (Exception e) {
            Log.e(TAG, "getCropRect error", e);
            return null;
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void setupImageReader() {
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        mImageReader =
                ImageReader.newInstance(
                        mCaptureSize.getWidth(), mCaptureSize.getHeight(), ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            reader -> {
                Image image = null;
                try {
                    image = reader.acquireNextImage();
                    if (image == null) return;
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    saveJpeg(data);
                    runOnUiThread(
                            () -> Toast.makeText(this, "Saved photo", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    Log.e(TAG, "Failed saving image: ", e);
                } finally {
                    if (image != null) image.close();
                }
            };

    private void saveJpeg(byte[] data) {
        try {
            String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";

            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);

            android.net.Uri uri =
                    getContentResolver()
                            .insert(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    values);

            if (uri != null) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(data);
                    out.flush();
                }
                // Make visible to media apps
                values.clear();
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);

                // Legacy devices: trigger immediate indexing
                try {
                    sendBroadcast(
                            new android.content.Intent(
                                    android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "saveJpeg error", e);
        }
    }

    // -------- Still capture flow --------

    // Call from the shutter button
    private void takePicture() {
        if (mCameraDevice == null || mCameraCaptureSession == null) return;

        // If AF is manual, skip AF lock; if AE is manual, skip AE pre-capture.
        if (!mIsFocusAuto && (!mIsIsoAuto || !mIsShutterAuto)) {
            // Full manual exposure and manual focus: capture immediately
            captureStillPicture();
        } else if (!mIsFocusAuto) {
            // Manual focus, auto exposure: skip AF lock, but allow AE pre-capture
            runPrecaptureSequence();
        } else if (!mIsIsoAuto || !mIsShutterAuto) {
            // Auto focus, manual exposure: lock AF only
            lockFocus(/*doAe*/ false);
        } else {
            // All auto: normal AF lock then AE pre-capture if needed
            lockFocus(/*doAe*/ true);
        }
    }

    private void lockFocus(boolean doAe) {
    try {
        // Trigger AF if AF is auto
        if (mIsFocusAuto) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);
            mState = STATE_WAITING_LOCK;
            // Store whether AE should run next by using a tag on the request
            mPreviewRequestBuilder.setTag(doAe ? "DO_AE" : "NO_AE");
            mCameraCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } else {
            // If focus is manual, skip to AE or capture
            if (doAe && mIsIsoAuto && mIsShutterAuto) {
                runPrecaptureSequence();
            } else {
                captureStillPicture();
            }
        }
    } catch (CameraAccessException e) {
        Log.e(TAG, "lockFocus error", e);
        captureStillPicture();
    }
}

    private void runPrecaptureSequence() {
    try {
        // Only meaningful if AE is ON
        if (mIsIsoAuto && mIsShutterAuto) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mState = STATE_WAITING_PRECAPTURE;
            mCameraCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } else {
            // AE is manual; skip pre-capture and go straight to capture
            captureStillPicture();
        }
    } catch (CameraAccessException e) {
        Log.e(TAG, "runPrecaptureSequence error", e);
        captureStillPicture();
    }
}

    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(TotalCaptureResult result) {
                    switch (mState) {
                        case STATE_WAITING_LOCK:
                            {
                                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                                if (afState == null
                                        || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                        || afState
                                                == CaptureResult
                                                        .CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                    if (aeState == null
                                            || aeState
                                                    == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                        mState = STATE_PICTURE_TAKEN;
                                        captureStillPicture();
                                    } else {
                                        runPrecaptureSequence();
                                    }
                                }
                                break;
                            }
                        case STATE_WAITING_PRECAPTURE:
                            {
                                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                                if (ae == null
                                        || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                        || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                    mState = STATE_WAITING_NON_PRECAPTURE;
                                }
                                break;
                            }
                        case STATE_WAITING_NON_PRECAPTURE:
                            {
                                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                                if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                    mState = STATE_PICTURE_TAKEN;
                                    captureStillPicture();
                                }
                                break;
                            }
                    }
                }

                @Override
                public void onCaptureProgressed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureResult partialResult) {}

                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    process(result);
                }

                @Override
                public void onCaptureFailed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureFailure failure) {
                    Log.e(TAG, "Capture failed: " + failure);
                }
            };

    private void captureStillPicture() {
    try {
        if (mCameraDevice == null) return;

        final CaptureRequest.Builder captureBuilder =
                mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(mImageReader.getSurface());

        // Exposure: if any manual flag is set, fully disable AE and provide both ISO and exposure.
        if (!mIsIsoAuto || !mIsShutterAuto) {
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            // Provide both to satisfy devices that require paired manual values
            int iso = mIsIsoAuto ? mIsoRange.getLower() : mCurrentIso;
            long exp = mIsShutterAuto ? Math.max(10_000_000L, mMinExposure) : mCurrentExposure;
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
        } else {
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        }

        // Focus: mirror preview state
        if (mIsFocusAuto) {
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } else {
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            Float focus = mPreviewRequestBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE);
            if (focus != null) {
                captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            }
        }

        // AWB same as preview
        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

        // Keep current zoom/crop
        captureBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                mPreviewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));

        // Orientation
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());

        CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                           @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
                unlockFocus();
            }
        };

        // Prioritize still
        mCameraCaptureSession.stopRepeating();
        mCameraCaptureSession.abortCaptures();
        mCameraCaptureSession.capture(captureBuilder.build(), cb, mBackgroundHandler);
    } catch (CameraAccessException e) {
        Log.e(TAG, "captureStillPicture error", e);
        unlockFocus();
    }
}

    private void unlockFocus() {
    try {
        // Reset triggers regardless of AF/AE modes
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
        mCameraCaptureSession.capture(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        mState = STATE_PREVIEW;
        updatePreview();
    } catch (CameraAccessException e) {
        Log.e(TAG, "unlockFocus error", e);
    }
}

    private int getJpegOrientation() {
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees;
        switch (deviceRotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                degrees = 0;
        }
        if (mIsFrontFacing) {
            // Front camera orientation calculation
            return (mSensorOrientation + degrees) % 360;
        } else {
            // Back camera orientation calculation
            return (mSensorOrientation - degrees + 360) % 360;
        }
    }

    // Utility comparator
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight()
                            - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
