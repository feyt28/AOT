/*
 * Copyright 2017 The Android Things Samples Authors.
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
package com.example.androidthings.imageclassifier;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.androidthings.imageclassifier.classifier.Classifier;
import com.example.androidthings.imageclassifier.classifier.TensorFlowImageClassifier;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import de.hdodenhof.circleimageview.CircleImageView;

public class ImageClassifierActivity extends Activity implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "ImageClassifierActivity";

    private ImagePreprocessor mImagePreprocessor;
    private TextToSpeech mTtsEngine;
    private TtsSpeaker mTtsSpeaker;
    private CameraHandler mCameraHandler;
    private TensorFlowImageClassifier mTensorFlowClassifier;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private HandlerThread mTimerThread;
    private Handler mTimerHandler;

    private CircleImageView mImage;
    //private ImageView mImage;
    private TextView[] mResultViews;

    private AtomicBoolean mReady = new AtomicBoolean(false);
    private ButtonInputDriver mButtonDriver;
    private Gpio mReadyLED;

    private static final long TIME_SEC = 1000;
    private static final long TIME_MIN = 60;

    private long TIME_TRIGGER;
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);
        mImage = (CircleImageView) findViewById(R.id.imageView);
        mResultViews = new TextView[3];
        mResultViews[0] = (TextView) findViewById(R.id.result1);
        mResultViews[1] = (TextView) findViewById(R.id.result2);
        mResultViews[2] = (TextView) findViewById(R.id.result3);

        getServerSettings();
        //init();
    }

    private void getServerSettings(){
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference settingsRef = database.getReference("Settings");
        settingsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.d(TAG, "Time Trigger from server: " + dataSnapshot.child("time_trigger").getValue());
                int server_trigger = Integer.parseInt(dataSnapshot.child("time_trigger").getValue().toString());
                TIME_TRIGGER = TIME_SEC * TIME_MIN * server_trigger;
                Log.d(TAG, "Time Trigger: " + TIME_TRIGGER);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void init() {

        if (isAndroidThingsDevice(this)) {
            initPIO();
        }

        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();

        mTimerThread = new HandlerThread("TimerThread");
        mTimerThread.start();

        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(mInitializeOnBackground);

        mTimerHandler = new Handler(mTimerThread.getLooper());
        mTimerHandler.postDelayed(mBackgroundTimerHandler, TIME_TRIGGER);

        setReady(true);
        // Let the user touch the screen to take a photo
        findViewById(R.id.container).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mReady.get()) {
                    progressDialog = new ProgressDialog(ImageClassifierActivity.this);
                    progressDialog.setIndeterminate(true);
                    progressDialog.setMessage("Processing Image");
                    progressDialog.show();
                    setReady(false);
                    mBackgroundHandler.post(mBackgroundClickHandler);
                } else {
                    Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
                }
            }
        });
    }

    /**
     * This method should only be called when running on an Android Things device.
     */
    private void initPIO() {
        PeripheralManagerService pioService = new PeripheralManagerService();
        try {
            mReadyLED = pioService.openGpio(BoardDefaults.getGPIOForLED());
            mReadyLED.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mButtonDriver = new ButtonInputDriver(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_ENTER);
            mButtonDriver.register();
        } catch (IOException e) {
            mButtonDriver = null;
            Log.w(TAG, "Could not open GPIO pins", e);
        }
    }

    private Runnable mInitializeOnBackground = new Runnable() {
        @Override
        public void run() {
            mImagePreprocessor = new ImagePreprocessor();

            mTtsSpeaker = new TtsSpeaker();
            mTtsSpeaker.setHasSenseOfHumor(true);
            mTtsEngine = new TextToSpeech(ImageClassifierActivity.this,
                    new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            if (status == TextToSpeech.SUCCESS) {
                                mTtsEngine.setLanguage(Locale.US);
                                mTtsEngine.setOnUtteranceProgressListener(utteranceListener);
                                mTtsSpeaker.speakReady(mTtsEngine);
                            } else {
                                Log.w(TAG, "Could not open TTS Engine (onInit status=" + status
                                        + "). Ignoring text to speech");
                                mTtsEngine = null;
                            }
                        }
                    });
            mCameraHandler = CameraHandler.getInstance();
            mCameraHandler.initializeCamera(
                    ImageClassifierActivity.this, mBackgroundHandler,
                    ImageClassifierActivity.this);

            mTensorFlowClassifier = new TensorFlowImageClassifier(ImageClassifierActivity.this);

            setReady(true);
        }
    };

    //TODO create handler for the timer
    private Runnable mBackgroundTimerHandler = new Runnable() {
        @Override
        public void run() {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    progressDialog = new ProgressDialog(ImageClassifierActivity.this);
                    progressDialog.setIndeterminate(true);
                    progressDialog.setMessage("Processing Image");
                    progressDialog.show();
                }
            });

            setReady(false);
            Log.d("Timer", "Automatic Capture set on Timer");
            processRecognition();
            mBackgroundHandler.postDelayed(this, TIME_TRIGGER);
        }
    };

    private Runnable mBackgroundClickHandler = new Runnable() {
        @Override
        public void run() {

            if (mTtsEngine != null) {
                mTtsSpeaker.speakShutterSound(mTtsEngine);
            }
            boolean result = mCameraHandler.takePicture();
            if(!result){
                processRecognition();
            }
        }
    };

    private UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            //setReady(false);
        }

        @Override
        public void onDone(String utteranceId) {
            //setReady(true);
        }

        @Override
        public void onError(String utteranceId) {
            //setReady(true);
        }
    };

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Received key up: " + keyCode + ". Ready = " + mReady.get());
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (mReady.get()) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setIndeterminate(true);
                progressDialog.setMessage("Processing Image");
                progressDialog.show();
                setReady(false);
                mBackgroundHandler.post(mBackgroundClickHandler);
            } else {
                Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    public void setReady(boolean ready) {
        mReady.set(ready);
        if (mReadyLED != null) {
            try {
                mReadyLED.setValue(ready);
            } catch (IOException e) {
                Log.w(TAG, "Could not set LED", e);
            }
        }
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        final Bitmap bitmap;
        try (Image image = reader.acquireNextImage()) {

            bitmap = mImagePreprocessor.preprocessImage(image);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImage.setImageBitmap(bitmap);
            }
        });

        final List<Classifier.Recognition> results = mTensorFlowClassifier.doRecognize(bitmap);

        Log.d(TAG, "Got the following results from Tensorflow: " + results);
        if (mTtsEngine != null) {
            // speak out loud the result of the image recognition
            mTtsSpeaker.speakResults(mTtsEngine, results);
        } else {
            // if theres no TTS, we don't need to wait until the utterance is spoken, so we set
            // to ready right away.
            //setReady(true);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mResultViews.length; i++) {
                    if (results.size() > i) {
                        Classifier.Recognition r = results.get(i);
                        //mResultViews[i].setText(r.getTitle() + " : " + r.getConfidence().toString());
                        mResultViews[i].setText(r.getTitle());
                    } else {
                        mResultViews[i].setText(null);
                    }
                }
                progressDialog.dismiss();
                setReady(true);
            }
        });
    }

    private void processRecognition(){
        final Bitmap bitmap;

        //randomize images
        Random rn = new Random();
        switch (rn.nextInt(16) + 1){
            case 1:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.bradpitt);
                break;
            case 2:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.angelina);
                break;
            case 3:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.rick);
                break;
            case 4:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.rick1);
                break;
            case 5:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.rick2);
                break;
            case 6:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.rick3);
                break;
            case 7:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.rick4);
                break;
            case 8:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.brad1);
                break;
            case 9:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.brad2);
                break;
            case 10:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.brad3);
                break;
            case 12:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.brad4);
                break;
            case 13:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.angelina1);
                break;
            case 14:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.angelina2);
                break;
            case 15:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.angelina3);
                break;
            case 16:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.angelina4);
                break;
            default:
                bitmap = BitmapFactory.decodeResource(this.getResources(), R.drawable.rick);
                break;
        }

        final List<Classifier.Recognition> results = mTensorFlowClassifier.doRecognize(bitmap);

        Log.d(TAG, "Got the following results from Tensorflow: " + results);
        if (mTtsEngine != null) {
            // speak out loud the result of the image recognition
            mTtsSpeaker.speakResults(mTtsEngine, results);
        } else {
            // if theres no TTS, we don't need to wait until the utterance is spoken, so we set
            // to ready right away.
            //setReady(true);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImage.setImageBitmap(bitmap);
                for (int i = 0; i < mResultViews.length; i++) {
                    if (results.size() > i) {
                        Classifier.Recognition r = results.get(i);
                        //mResultViews[i].setText(r.getTitle() + " : " + r.getConfidence().toString());
                        mResultViews[i].setText(r.getTitle() );
                    } else {
                        mResultViews[i].setText(null);
                    }
                }
                try{
                    if(progressDialog.isShowing()){
                        progressDialog.dismiss();
                    }
                }catch (Exception e){
                }
                setReady(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mBackgroundThread != null) mBackgroundThread.quit();
        } catch (Throwable t) {
            // close quietly
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;

        try {
            if (mCameraHandler != null) mCameraHandler.shutDown();
        } catch (Throwable t) {
            // close quietly
        }
        try {
            if (mTensorFlowClassifier != null) mTensorFlowClassifier.destroyClassifier();
        } catch (Throwable t) {
            // close quietly
        }
        try {
            if (mButtonDriver != null) mButtonDriver.close();
        } catch (Throwable t) {
            // close quietly
        }

        if (mTtsEngine != null) {
            mTtsEngine.stop();
            mTtsEngine.shutdown();
        }
    }

    /**
     * @return true if this device is running Android Things.
     *
     * Source: https://stackoverflow.com/a/44171734/112705
     */
    private boolean isAndroidThingsDevice(Context context) {
        // We can't use PackageManager.FEATURE_EMBEDDED here as it was only added in API level 26,
        // and we currently target a lower minSdkVersion
        final PackageManager pm = context.getPackageManager();
        boolean isRunningAndroidThings = pm.hasSystemFeature("android.hardware.type.embedded");
        Log.d(TAG, "isRunningAndroidThings: " + isRunningAndroidThings);
        return isRunningAndroidThings;
    }
}
