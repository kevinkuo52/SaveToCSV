/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tzutalin.dlibtest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.WindowManager;

import com.tzutalin.dlib.Constants;
import com.tzutalin.dlib.FaceDet;
import com.tzutalin.dlib.VisionDetRet;

import junit.framework.Assert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import jsat.datatransform.FastICA;

import static java.lang.StrictMath.abs;


import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.Arrays;

import static_proxy.PyMathLib.*;
/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
public class OnGetImageListener implements OnImageAvailableListener {
    private static final boolean SAVE_PREVIEW_BITMAP = false;

    private static final int INPUT_SIZE = 224;
    private static final String TAG = "OnGetImageListener";

    private int mScreenRotation = 90;

    private int mPreviewWdith = 0;
    private int mPreviewHeight = 0;
    private byte[][] mYUVBytes;
    private int[] mRGBBytes = null;
    private Bitmap mRGBframeBitmap = null;
    private Bitmap mCroppedBitmap = null;

    private List<Integer> meanlist = new ArrayList<>();
    private int cutlow = 0;

    private boolean mIsComputing = false;
    private Handler mInferenceHandler;

    private Context mContext;
    private FaceDet mFaceDet;
    private TrasparentTitleView mTransparentTitleView;
    private FloatingCameraWindow mWindow;
    private Paint mFaceLandmardkPaint;
    private Paint mFaceKalmanPaint;


    public void initialize(
            final Context context,
            final AssetManager assetManager,
            final TrasparentTitleView scoreView,
            final Handler handler) {
        this.mContext = context;
        this.mTransparentTitleView = scoreView;
        this.mInferenceHandler = handler;
        mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());
        mWindow = new FloatingCameraWindow(mContext);

        mFaceLandmardkPaint = new Paint();
        mFaceLandmardkPaint.setColor(Color.GREEN);
        mFaceLandmardkPaint.setStrokeWidth(2);
        mFaceLandmardkPaint.setStyle(Paint.Style.STROKE);

        mFaceKalmanPaint = new Paint();
        mFaceKalmanPaint.setColor(Color.RED);
        mFaceKalmanPaint.setStrokeWidth(5);
        mFaceKalmanPaint.setStyle(Paint.Style.STROKE);
    }

    public void deInitialize() {
        synchronized (OnGetImageListener.this) {
            if (mFaceDet != null) {
                mFaceDet.release();
            }

            if (mWindow != null) {
                mWindow.release();
            }
        }
    }

    private void drawResizedBitmap(final Bitmap src, final Bitmap dst) {

        Display getOrient = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation = Configuration.ORIENTATION_UNDEFINED;
        Point point = new Point();
        getOrient.getSize(point);
        int screen_width = point.x;
        int screen_height = point.y;
        Log.d(TAG, String.format("screen size (%d,%d)", screen_width, screen_height));
        if (screen_width < screen_height) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
            mScreenRotation = 90;
        } else {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
            mScreenRotation = 0;
        }

        Assert.assertEquals(dst.getWidth(), dst.getHeight());
        final float minDim = Math.min(src.getWidth(), src.getHeight());

        final Matrix matrix = new Matrix();

        // We only want the center square out of the original rectangle.
        final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
        final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
        matrix.preTranslate(translateX, translateY);

        final float scaleFactor = dst.getHeight() / minDim;
        matrix.postScale(scaleFactor, scaleFactor);

        // Rotate around the center if necessary.
        if (mScreenRotation != 0) {
            matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
            matrix.postRotate(mScreenRotation);
            matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
        }

        final Canvas canvas = new Canvas(dst);
        canvas.drawBitmap(src, matrix, null);
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            // No mutex needed as this method is not reentrant.
            if (mIsComputing) {
                image.close();
                return;
            }
            mIsComputing = true;

            Trace.beginSection("imageAvailable");

            final Plane[] planes = image.getPlanes();

            // Initialize the storage bitmaps once when the resolution is known.
            if (mPreviewWdith != image.getWidth() || mPreviewHeight != image.getHeight()) {
                mPreviewWdith = image.getWidth();
                mPreviewHeight = image.getHeight();

                Log.d(TAG, String.format("Initializing at size %dx%d", mPreviewWdith, mPreviewHeight));
                mRGBBytes = new int[mPreviewWdith * mPreviewHeight];
                mRGBframeBitmap = Bitmap.createBitmap(mPreviewWdith, mPreviewHeight, Config.ARGB_8888);
                 mCroppedBitmap= Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

                mYUVBytes = new byte[planes.length][];
                for (int i = 0; i < planes.length; ++i) {
                    mYUVBytes[i] = new byte[planes[i].getBuffer().capacity()];
                }
            }

            for (int i = 0; i < planes.length; ++i) {
                planes[i].getBuffer().get(mYUVBytes[i]);
            }

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    mYUVBytes[0],
                    mYUVBytes[1],
                    mYUVBytes[2],
                    mRGBBytes,
                    mPreviewWdith,
                    mPreviewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false);

            image.close();
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            Log.e(TAG, "Exception!", e);
            Trace.endSection();
            return;
        }

        mRGBframeBitmap.setPixels(mRGBBytes, 0, mPreviewWdith, 0, 0, mPreviewWdith, mPreviewHeight);
        drawResizedBitmap(mRGBframeBitmap, mCroppedBitmap);

        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(mCroppedBitmap);
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        mInferenceHandler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!new File(Constants.getFaceShapeModelPath()).exists()) {
                            mTransparentTitleView.setText("Copying landmark model to " + Constants.getFaceShapeModelPath());
                            FileUtils.copyFileFromRawToOthers(mContext, R.raw.shape_predictor_68_face_landmarks, Constants.getFaceShapeModelPath());
                        }

                        long startTime = System.currentTimeMillis();
                        List<VisionDetRet> results;
                        synchronized (OnGetImageListener.this) {
                            results = mFaceDet.detect(mCroppedBitmap);
                        }
                        long endTime = System.currentTimeMillis();
                        //mTransparentTitleView.setText("Time cost: " + String.valueOf((endTime - startTime) / 1000f) + " sec");
                        // Draw on bitmap
                        if (results != null) {

                            String fileTitle = dateFormat.format(new Date()); // Find todays date
                            writeToFile(fileTitle, "");

                            for (final VisionDetRet ret : results) {
                                float resizeRatio = 1.0f;
                                Rect bounds = new Rect();
                                bounds.left = (int) (ret.getLeft() * resizeRatio);
                                bounds.top = (int) (ret.getTop() * resizeRatio);
                                bounds.right = (int) (ret.getRight() * resizeRatio);
                                bounds.bottom = (int) (ret.getBottom() * resizeRatio);

                                Canvas canvas = new Canvas(mCroppedBitmap);
                                canvas.drawRect(bounds, mFaceLandmardkPaint);


                                // Draw landmark
                                ArrayList<Point> landmarks = ret.getFaceLandmarks();

                                int counter = 0;
                                for (Point point : landmarks) {
                                    int pointX = (int) (point.x * resizeRatio);
                                    int pointY = (int) (point.y * resizeRatio);
                                    canvas.drawCircle(pointX, pointY, 2, mFaceLandmardkPaint);

                                    counter = counter + 1;
                                }


                                Rect listforehead = new Rect();
                                listforehead.left = (int) (ret.getLeft() + (((ret.getRight() - ret.getLeft()) / 2.0f) - 17));
                                listforehead.top = (int) ((ret.getTop() - 5) * resizeRatio);
                                listforehead.right = (int) (ret.getLeft() + (((ret.getRight() - ret.getLeft()) / 2.0f) + 17));
                                listforehead.bottom = (int) ((ret.getTop() + 17 )* resizeRatio);

                                canvas.drawRect(listforehead, mFaceLandmardkPaint);

                                Rect listleftface = new Rect();
                                listleftface.left = (int) (ret.getLeft() + (((ret.getRight() - ret.getLeft()) / 2.0f) - 50));
                                listleftface.top = (int) ((ret.getTop()+60) * resizeRatio);
                                listleftface.right = (int) (ret.getLeft() + (((ret.getRight() - ret.getLeft()) / 2.0f) - 20));
                                listleftface.bottom = (int) ((ret.getTop() + 85 )* resizeRatio);

                                canvas.drawRect(listleftface, mFaceLandmardkPaint);

                                Rect listrightface = new Rect();
                                listrightface.left = (int) (ret.getLeft() + (((ret.getRight() - ret.getLeft()) / 2.0f) + 20));
                                listrightface.top = (int) ((ret.getTop()+60) * resizeRatio);
                                listrightface.right = (int) (ret.getLeft() + (((ret.getRight() - ret.getLeft()) / 2.0f) + 50));
                                listrightface.bottom = (int) ((ret.getTop() + 85 )* resizeRatio);

                                canvas.drawRect(listrightface, mFaceLandmardkPaint);

                                ArrayList<Tuple> avearray2 = new ArrayList<>();
                                ArrayList<Tuple>  allPoints = new ArrayList<>();

                                int a1 = ((listforehead.left + listforehead.right)/2);
                                int b1 = ((listleftface.left + listleftface.right)/2);
                                int c1 = ((listrightface.left + listrightface.right)/2);

                                int a3 = ((listforehead.bottom + listforehead.top)/2);
                                int b3 = ((listleftface.bottom + listleftface.top)/2);
                                int c3 = ((listrightface.bottom + listrightface.top)/2);

                                canvas.drawCircle(a1, a3, 3, mFaceKalmanPaint);
                                canvas.drawCircle(b1, b3, 3, mFaceKalmanPaint);
                                canvas.drawCircle(c1, c3, 3, mFaceKalmanPaint);

                                int a2, b2,c2;

                                for (int y1 = 0;  y1 <5; y1++)
                                {
                                    a2 = ((listforehead.bottom + listforehead.top)/2) + y1;
                                    b2 = ((listleftface.bottom + listleftface.top)/2) + y1;
                                    c2 = ((listrightface.bottom + listrightface.top)/2) + y1;

                                    Tuple tup1 = new Tuple(a1, a2);
                                    Tuple tup2 = new Tuple(b1, b2);
                                    Tuple tup3 = new Tuple(c1, c2);

                                    allPoints.add(tup1);
                                    allPoints.add(tup2);
                                    allPoints.add(tup3);

                                }

//                                Tuple topRight = new Tuple(bounds.top, bounds.right);
//                                Tuple bottomRight = new Tuple(bounds.bottom, bounds.right);
//                                Tuple topLeft = new Tuple(bounds.top, bounds.left);
//                                Tuple bottomLeft = new Tuple(bounds.bottom, bounds.left);


                                ArrayList<Point> threekeypoints = new ArrayList<>();

                                Point foreheadpoint = new Point(a1, a3);
                                Point leftfacepoint = new Point(b1, b3);
                                Point rightfacepoint = new Point(c1, c3);

                                threekeypoints.add(foreheadpoint);
                                threekeypoints.add(leftfacepoint);
                                threekeypoints.add(rightfacepoint);

                                int foreheadw = abs((int) (listforehead.left - listforehead.right) / 4);
                                int leftfacew = abs((int) (listleftface.left - listleftface.right) / 4);
                                int rightfacew = abs((int) (listrightface.left - listrightface.right) / 4);

                                int foreheadh = abs((int) (listforehead.top - listforehead.bottom) / 4);
                                int leftfaceh = abs((int) (listleftface.top - listleftface.bottom) / 4);
                                int rightfaceh = abs((int) (listrightface.top - listrightface.bottom) / 4);


                                List<Float> Normalizedlist = new ArrayList<>();


                                for (int i = a1-foreheadw; i < (a1 + foreheadw); i++){
                                    for (int j=a3-foreheadh; j < (a3 + foreheadh); j++){
                                        int colorbit = mCroppedBitmap.getPixel(i,j);
                                        int red = Color.red(colorbit);
                                        int blue = Color.blue(colorbit);
                                        int green = Color.green(colorbit);
                                        float[] hsv = new float[3];
                                        Color.RGBToHSV(red, green, blue, hsv);
                                        //Log.d(TAG, String.format("HUE (%f)", hsv[0]));
                                        Normalizedlist.add(hsv[0]);
                                    }
                                }

                                for (int i = b1-foreheadw; i < (b1 + foreheadw); i++){
                                    for (int j=b3-foreheadh; j < (b3 + foreheadh); j++){
                                        int colorbit = mCroppedBitmap.getPixel(i,j);
                                        int red = Color.red(colorbit);
                                        int blue = Color.blue(colorbit);
                                        int green = Color.green(colorbit);
                                        float[] hsv = new float[3];
                                        Color.RGBToHSV(red, green, blue, hsv);
                                        //Log.d(TAG, String.format("HUE (%f)", hsv[0]));
                                        Normalizedlist.add(hsv[0]);
                                    }
                                }

                                for (int i = c1-foreheadw; i < (c1 + foreheadw); i++){
                                    for (int j=c3-foreheadh; j < (c3 + foreheadh); j++){
                                        int colorbit = mCroppedBitmap.getPixel(i,j);
                                        int red = Color.red(colorbit);
                                        int blue = Color.blue(colorbit);
                                        int green = Color.green(colorbit);
                                        float[] hsv = new float[3];
                                        Color.RGBToHSV(red, green, blue, hsv);
                                        //Log.d(TAG, String.format("HUE (%f)", hsv[0]));
                                        Normalizedlist.add(hsv[0]);
                                    }
                                }
                                float sum = 0;
                                for(int i = 0; i < Normalizedlist.size(); i++){
                                    sum += Normalizedlist.get(i);
                                }
                                int totalmean = (int) (sum / Normalizedlist.size());
                                meanlist.add(totalmean);


                                if(meanlist.size() >= 150 + cutlow){
                                    int windowstart = meanlist.size() - 150;
                                    List<Integer> window = new ArrayList<>(meanlist.subList(windowstart, windowstart + 150));
                                    List<Double> doubleWindow = new ArrayList<>();

                                    int wsum = 0;
                                    float wmean;
                                    double sdtemp = 0;

                                    for(int i = 0; i < window.size(); i++){
                                        wsum += window.get(i);
                                    }
                                    wmean = (float) (wsum / window.size());

                                    for(int i = 0; i < window.size(); i++){
                                        double squrDiffToMean = Math.pow(window.get(i) - wmean, 2);
                                        sdtemp += squrDiffToMean;
                                    }

                                    double meanOfDiffs = sdtemp / (double) (window.size());
                                    double stdv = Math.sqrt(meanOfDiffs);

                                    for(int i = 0; i < window.size(); i++){
                                        double updateVal = (((double)(window.get(i)) - wmean) / stdv);
                                        doubleWindow.add(updateVal);
                                    }

                                    double[] window = new double[150];

                                    if (! Python.isStarted()) {
                                        Python.start(new AndroidPlatform(getApplicationContext()));
                                    }
                                    Python py = Python.getInstance();
                                    PyObject NS = py.getModule("static_proxy.PyMathLib").get("NpScipy");
                                    PyObject ns_po = NS.call();
                                    NpScipy npScipy = ns_po.toJava(NpScipy.class);

                                    double[][] detrend = npScipy.get_detrend(window,true);
                                    double fs = 30.0;
                                    double lowcut = 0.75;
                                    double highcut = 4.0;
                                    int order = 4;
                                    double[][] y = npScipy.butter_bandpass_filter(detrend, lowcut,highcut, fs, order);
                                    double[][] powerSpec = npScipy.get_powerSpec(y, true);
                                    double[] freq = npScipy.fftfreq(150, (1.0/30));

                                    FastICA ica = new FastICA();





                                    Log.d(TAG, String.format("Kan : (%f)", doubleWindow.get(1)));


                                    cutlow = cutlow + 5;

                                    String timeStamp = dateFormat.format(new Date()); // Find todays date
                                    writeToFile(fileTitle, timeStamp+","+pulseRate+"\n");





                                }



                            }
                        }
                        mWindow.setRGBBitmap(mCroppedBitmap);
                        mIsComputing = false;
                    }
                });

        Trace.endSection();
    }
    public void writeToFile(String fileName, String body)
    {
        FileOutputStream fos = null;

        try {
            final File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath());

            if (!dir.exists())
            {
                if(!dir.mkdirs()){
                    Log.e("ALERT","could not create the directories");
                }
            }

            final File myFile = new File(dir, fileName + ".csv");

            if (!myFile.exists())
            {
                myFile.createNewFile();
            }

            fos = new FileOutputStream(myFile);

            fos.write(body.getBytes());
            fos.close();
            Toast.makeText(getBaseContext(),
                    "Done writing SD"+Environment.getExternalStorageDirectory().getAbsolutePath(),
                    Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            Toast.makeText(getBaseContext(), e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
