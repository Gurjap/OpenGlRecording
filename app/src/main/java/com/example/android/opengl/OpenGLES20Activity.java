/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.example.android.opengl;

import android.app.Activity;
import android.graphics.Path;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.example.android.opengl.gles.Drawable2d;
import com.example.android.opengl.gles.EglCore;
import com.example.android.opengl.gles.FlatShadedProgram;
import com.example.android.opengl.gles.FullFrameRect;
import com.example.android.opengl.gles.GlUtil;
import com.example.android.opengl.gles.Sprite2d;
import com.example.android.opengl.gles.Texture2dProgram;
import com.example.android.opengl.gles.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/*
 * There is not much here, but at the bottom, it setups immersive Mode, so that only the app
 * shows, and the use needs to swipe up from the bottom to get the navigation buttons to appear.
 */

public class OpenGLES20Activity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {




    // mMVPMatrix is an abbreviation for "Model View Projection Matrix"
    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mRotationMatrix = new float[16];






    private static final String TAG = "OpenGLActivity";
    private SurfaceView mGLView;
    private static final int RECMETHOD_DRAW_TWICE = 0;
    private static final int RECMETHOD_FBO = 1;
    private static final int RECMETHOD_BLIT_FRAMEBUFFER = 2;

    private boolean mRecordingEnabled = false;          // controls button state
    private boolean mBlitFramebufferAllowed = false;    // requires GLES3
    private int mSelectedRecordMethod;                  // current radio button

    private RenderThread mRenderThread;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create a GLSurfaceView instance and set it
        // as the ContentView for this Activity
        mSelectedRecordMethod = RECMETHOD_FBO;
        setContentView(R.layout.activity);
        mGLView=findViewById(R.id.fboActivity_surfaceView);
        mGLView.getHolder().addCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // TODO: we might want to stop recording here.  As it is, we continue "recording",
        //       which is pretty boring since we're not outputting any frames (test this
        //       by blanking the screen with the power button).

        // If the callback was posted, remove it.  This stops the notifications.  Ideally we
        // would send a message to the thread letting it know, so when it wakes up it can
        // reset its notion of when the previous Choreographer event arrived.
        Log.d(TAG, "onPause unhooking choreographer");
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If we already have a Surface, we just need to resume the frame notifications.
        if (mRenderThread != null) {
            Log.d(TAG, "onResume re-hooking choreographer");
            Choreographer.getInstance().postFrameCallback(this);
        }

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);  //this line is api 19+
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

        Log.d(TAG, "surfaceCreated holder=" + holder);
        File outputFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "/fbo-gl-recording.mp4");

        SurfaceView sv = (SurfaceView) findViewById(R.id.fboActivity_surfaceView);
        mRenderThread = new RenderThread(sv.getHolder(), new ActivityHandler(this), outputFile,
                MiscUtils.getDisplayRefreshNsec(this));
        mRenderThread.setName("RecordFBO GL render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();
        mRenderThread.setRecordMethod(mSelectedRecordMethod);

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceCreated();
        }

        // start the draw events
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);




        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceChanged(format, width, height);
        }
    }
    /**
     * Utility method for compiling a OpenGL shader.
     *
     * <p><strong>Note:</strong> When developing shaders, use the checkGlError()
     * method to debug shader coding errors.</p>
     *
     * @param type - Vertex or fragment shader type.
     * @param shaderCode - String containing the shader code.
     * @return - Returns an id for the shader.
     */
    public static int loadShader(int type, String shaderCode){

        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        int shader = GLES20.glCreateShader(type);

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        return shader;
    }

    /**
     * Utility method for debugging OpenGL calls. Provide the name of the call
     * just after making it:
     *
     * <pre>
     * mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor");
     * MyGLRenderer.checkGlError("glGetUniformLocation");</pre>
     *
     * If the operation is not successful, the check throws an error.
     *
     * @param glOperation - Name of the OpenGL call to check.
     */
    public static void checkGlError(String glOperation) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, glOperation + ": glError " + error);
            throw new RuntimeException(glOperation + ": glError " + error);
        }
    }



    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // We need to wait for the render thread to shut down before continuing because we
        // don't want the Surface to disappear out from under it mid-render.  The frame
        // notifications will have been stopped back in onPause(), but there might have
        // been one in progress.
        //
        // TODO: the RenderThread doesn't currently wait for the encoder / muxer to stop,
        //       so we can't use this as an indication that the .mp4 file is complete.

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendShutdown();
            try {
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        mRenderThread = null;
        mRecordingEnabled = false;

        // If the callback was posted, remove it.  Without this, we could get one more
        // call on doFrame().
        Choreographer.getInstance().removeFrameCallback(this);
        Log.d(TAG, "surfaceDestroyed complete");
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            Choreographer.getInstance().postFrameCallback(this);
            rh.sendDoFrame(frameTimeNanos);
        }
    }
    /**
     * onClick handler for "record" button.
     * <p>
     * Ideally we'd grey out the button while in a state of transition, e.g. while the
     * MediaMuxer finishes creating the file, and in the (very brief) period before the
     * SurfaceView's surface is created.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        Log.d(TAG, "clickToggleRecording");
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            mRecordingEnabled = !mRecordingEnabled;
            rh.setRecordingEnabled(mRecordingEnabled);
        }
    }


    /**
     * Handles messages sent from the render thread to the UI thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.
     */
    static class ActivityHandler extends Handler {
        private static final int MSG_GLES_VERSION = 0;
        private static final int MSG_UPDATE_FPS = 1;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<OpenGLES20Activity> mWeakActivity;

        public ActivityHandler(OpenGLES20Activity activity) {
            mWeakActivity = new WeakReference<OpenGLES20Activity>(activity);
        }

        /**
         * Send the GLES version.
         * <p>
         * Call from non-UI thread.
         */
        public void sendGlesVersion(int version) {
            sendMessage(obtainMessage(MSG_GLES_VERSION, version, 0));
        }

        /**
         * Send an FPS update.  "fps" should be in thousands of frames per second
         * (i.e. fps * 1000), so we can get fractional fps even though the Handler only
         * supports passing integers.
         * <p>
         * Call from non-UI thread.
         */
        public void sendFpsUpdate(int tfps, int dropped) {
            sendMessage(obtainMessage(MSG_UPDATE_FPS, tfps, dropped));
        }

        @Override  // runs on UI thread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "ActivityHandler [" + this + "]: what=" + what);

            OpenGLES20Activity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "ActivityHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_GLES_VERSION:
                    //activity.handleShowGlesVersion(msg.arg1);
                    break;
                case MSG_UPDATE_FPS:
                    //activity.handleUpdateFps(msg.arg1, msg.arg2);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }


    /**
     * This class handles all OpenGL rendering.
     * <p>
     * We use Choreographer to coordinate with the device vsync.  We deliver one frame
     * per vsync.  We can't actually know when the frame we render will be drawn, but at
     * least we get a consistent frame interval.
     * <p>
     * Start the render thread after the Surface has been created.
     */
    private static class RenderThread extends Thread {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        private volatile RenderHandler mHandler;

        // Handler we can send messages to if we want to update the app UI.
        private ActivityHandler mActivityHandler;

        // Used to wait for the thread to start.
        private Object mStartLock = new Object();
        private boolean mReady = false;

        private volatile SurfaceHolder mSurfaceHolder;  // may be updated by UI thread
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;
        private FlatShadedProgram mProgram;

        // Orthographic projection matrix.
        private float[] mDisplayProjectionMatrix = new float[16];

        private final Drawable2d mTriDrawable = new Drawable2d(Drawable2d.Prefab.TRIANGLE);
        private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);

        // One spinning triangle, one bouncing rectangle, and four edge-boxes.
        private Sprite2d mTri;
        private Sprite2d mRect;
        private Sprite2d mEdges[];
        private Sprite2d mRecordRect;
        private float mRectVelX, mRectVelY;     // velocity, in viewport units per second
        private float mInnerLeft, mInnerTop, mInnerRight, mInnerBottom;

        private final float[] mIdentityMatrix;

        // Previous frame time.
        private long mPrevTimeNanos;

        // FPS / drop counter.
        private long mRefreshPeriodNanos;
        private long mFpsCountStartNanos;
        private int mFpsCountFrame;
        private int mDroppedFrames;
        private boolean mPreviousWasDropped;

        // Used for off-screen rendering.
        private int mOffscreenTexture;
        private int mFramebuffer;
        private int mDepthBuffer;
        private FullFrameRect mFullScreen;

        // Used for recording.
        private boolean mRecordingEnabled;
        private File mOutputFile;
        private WindowSurface mInputWindowSurface;
        private TextureMovieEncoder2 mVideoEncoder;
        private int mRecordMethod;
        private boolean mRecordedPrevious;
        private Rect mVideoRect;
        private Triangle mTriangle;
        private Square   mSquare;
        // mMVPMatrix is an abbreviation for "Model View Projection Matrix"
        private final float[] mMVPMatrix = new float[16];
        private final float[] mProjectionMatrix = new float[16];
        private final float[] mViewMatrix = new float[16];
        private final float[] mRotationMatrix = new float[16];
        private float mAngle;


        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         */
        public RenderThread(SurfaceHolder holder, ActivityHandler ahandler, File outputFile,
                            long refreshPeriodNs) {
            mSurfaceHolder = holder;
            mActivityHandler = ahandler;
            mOutputFile = outputFile;
            mRefreshPeriodNanos = refreshPeriodNs;

            mVideoRect = new Rect();

            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);


        }
        /**
         * Returns the rotation angle of the triangle shape (mTriangle).
         *
         * @return - A float representing the rotation angle.
         */
        public float getAngle() {
            return mAngle;
        }

        /**
         * Sets the rotation angle of the triangle shape (mTriangle).
         */
        public void setAngle(float angle) {
            mAngle = angle;
        }
        /**
         * Thread entry point.
         * <p>
         * The thread should not be started until the Surface associated with the SurfaceHolder
         * has been created.  That way we don't have to wait for a separate "surface created"
         * message to arrive.
         */
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new RenderHandler(this);
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);
            synchronized (mStartLock) {
                mReady = true;
                mStartLock.notify();    // signal waitUntilReady()
            }

            Looper.loop();

            Log.d(TAG, "looper quit");
            releaseGl();
            mEglCore.release();

            synchronized (mStartLock) {
                mReady = false;
            }
        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p>
         * Call from the UI thread.
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Shuts everything down.
         */
        private void shutdown() {
            Log.d(TAG, "shutdown");
            stopEncoder();
            Looper.myLooper().quit();
        }

        /**
         * Returns the render thread's Handler.  This may be called from any thread.
         */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /**
         * Prepares the surface.
         */
        private void surfaceCreated() {
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
        }

        /**
         * Prepares window surface and GL state.
         */
        private void prepareGl(Surface surface) {
            Log.d(TAG, "prepareGl");

            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();

            // Used for blitting texture to FBO.
            mFullScreen = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));

            // Program used for drawing onto the screen.
            mProgram = new FlatShadedProgram();



            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            mTriangle = new Triangle();
            mSquare   = new Square();

            // Set the background color.
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);


            mActivityHandler.sendGlesVersion(mEglCore.getGlVersion());
        }

        /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         */
        private void surfaceChanged(int width, int height) {
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            prepareFramebuffer(width, height);

            // Use full window.
            GLES20.glViewport(0, 0, width, height);

            // Adjust the viewport based on geometry changes,
            // such as screen rotation

            GLES20.glViewport(0, 0, width, height);

            float ratio = (float) width / height;

            // this projection matrix is applied to object coordinates
            // in the onDrawFrame() method
            Matrix.frustumM(mProjectionMatrix, 0, -ratio, ratio, -1, 1, 3, 7);

        }

        /**
         * Prepares the off-screen framebuffer.
         */
        private void prepareFramebuffer(int width, int height) {
            GlUtil.checkGlError("prepareFramebuffer start");

            int[] values = new int[1];

            // Create a texture object and bind it.  This will be the color buffer.
            GLES20.glGenTextures(1, values, 0);
            GlUtil.checkGlError("glGenTextures");
            mOffscreenTexture = values[0];   // expected > 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOffscreenTexture);
            GlUtil.checkGlError("glBindTexture " + mOffscreenTexture);

            // Create texture storage.
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            // Set parameters.  We're probably using non-power-of-two dimensions, so
            // some values may not be available for use.
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            // Create framebuffer object and bind it.
            GLES20.glGenFramebuffers(1, values, 0);
            GlUtil.checkGlError("glGenFramebuffers");
            mFramebuffer = values[0];    // expected > 0
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
            GlUtil.checkGlError("glBindFramebuffer " + mFramebuffer);

            // Create a depth buffer and bind it.
            GLES20.glGenRenderbuffers(1, values, 0);
            GlUtil.checkGlError("glGenRenderbuffers");
            mDepthBuffer = values[0];    // expected > 0
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glBindRenderbuffer " + mDepthBuffer);

            // Allocate storage for the depth buffer.
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                    width, height);
            GlUtil.checkGlError("glRenderbufferStorage");

            // Attach the depth buffer and the texture (color buffer) to the framebuffer object.
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glFramebufferRenderbuffer");
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, mOffscreenTexture, 0);
            GlUtil.checkGlError("glFramebufferTexture2D");

            // See if GLES is happy with all this.
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Framebuffer not complete, status=" + status);
            }

            // Switch back to the default framebuffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            GlUtil.checkGlError("prepareFramebuffer done");
        }

        /**
         * Releases most of the GL resources we currently hold.
         * <p>
         * Does not release EglCore.
         */
        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");

            int[] values = new int[1];

            if (mWindowSurface != null) {
                mWindowSurface.release();
                mWindowSurface = null;
            }
            if (mProgram != null) {
                mProgram.release();
                mProgram = null;
            }
            if (mOffscreenTexture > 0) {
                values[0] = mOffscreenTexture;
                GLES20.glDeleteTextures(1, values, 0);
                mOffscreenTexture = -1;
            }
            if (mFramebuffer > 0) {
                values[0] = mFramebuffer;
                GLES20.glDeleteFramebuffers(1, values, 0);
                mFramebuffer = -1;
            }
            if (mDepthBuffer > 0) {
                values[0] = mDepthBuffer;
                GLES20.glDeleteRenderbuffers(1, values, 0);
                mDepthBuffer = -1;
            }
            if (mFullScreen != null) {
                mFullScreen.release(false); // TODO: should be "true"; must ensure mEglCore current
                mFullScreen = null;
            }

            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /**
         * Updates the recording state.  Stops or starts recording as needed.
         */
        private void setRecordingEnabled(boolean enabled) {
            if (enabled == mRecordingEnabled) {
                return;
            }
            if (enabled) {
                startEncoder();
            } else {
                stopEncoder();
            }
            mRecordingEnabled = enabled;
        }

        /**
         * Changes the method we use to render frames to the encoder.
         */
        private void setRecordMethod(int recordMethod) {
            Log.d(TAG, "RT: setRecordMethod " + recordMethod);
            mRecordMethod = recordMethod;
        }

        /**
         * Creates the video encoder object and starts the encoder thread.  Creates an EGL
         * surface for encoder input.
         */
        private void startEncoder() {
            Log.d(TAG, "starting to record");
            // Record at 1280x720, regardless of the window dimensions.  The encoder may
            // explode if given "strange" dimensions, e.g. a width that is not a multiple
            // of 16.  We can box it as needed to preserve dimensions.
            final int BIT_RATE = 4000000;   // 4Mbps
            final int VIDEO_WIDTH = 1280;
            final int VIDEO_HEIGHT = 720;
            int windowWidth = mWindowSurface.getWidth();
            int windowHeight = mWindowSurface.getHeight();
            float windowAspect = (float) windowHeight / (float) windowWidth;
            int outWidth, outHeight;
            if (VIDEO_HEIGHT > VIDEO_WIDTH * windowAspect) {
                // limited by narrow width; reduce height
                outWidth = VIDEO_WIDTH;
                outHeight = (int) (VIDEO_WIDTH * windowAspect);
            } else {
                // limited by short height; restrict width
                outHeight = VIDEO_HEIGHT;
                outWidth = (int) (VIDEO_HEIGHT / windowAspect);
            }
            int offX = (VIDEO_WIDTH - outWidth) / 2;
            int offY = (VIDEO_HEIGHT - outHeight) / 2;
            mVideoRect.set(offX, offY, offX + outWidth, offY + outHeight);
            Log.d(TAG, "Adjusting window " + windowWidth + "x" + windowHeight +
                    " to +" + offX + ",+" + offY + " " +
                    mVideoRect.width() + "x" + mVideoRect.height());

            VideoEncoderCore encoderCore;
            try {
                encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                        BIT_RATE, mOutputFile);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
            mVideoEncoder = new TextureMovieEncoder2(encoderCore);
        }

        /**
         * Stops the video encoder if it's running.
         */
        private void stopEncoder() {
            if (mVideoEncoder != null) {
                Log.d(TAG, "stopping recorder, mVideoEncoder=" + mVideoEncoder);
                mVideoEncoder.stopRecording();
                // TODO: wait (briefly) until it finishes shutting down so we know file is
                //       complete, or have a callback that updates the UI
                mVideoEncoder = null;
            }
            if (mInputWindowSurface != null) {
                mInputWindowSurface.release();
                mInputWindowSurface = null;
            }
        }

        /**
         * Advance state and draw frame in response to a vsync event.
         */
        private void doFrame(long timeStampNanos) {
            // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
            // recording is too expensive, maybe the CPU frequency governor thinks we're
            // not doing and wants to drop the clock frequencies -- we need to drop frames
            // to catch up.  The "timeStampNanos" value is based on the system monotonic
            // clock, as is System.nanoTime(), so we can compare the values directly.
            //
            // Our clumsy collision detection isn't sophisticated enough to deal with large
            // time gaps, but it's nearly cost-free, so we go ahead and do the computation
            // either way.
            //
            // We can reduce the overhead of recording, as well as the size of the movie,
            // by recording at ~30fps instead of the display refresh rate.  As a quick hack
            // we just record every-other frame, using a "recorded previous" flag.

            update(timeStampNanos);

            long diff = System.nanoTime() - timeStampNanos;
            long max = mRefreshPeriodNanos - 2000000;   // if we're within 2ms, don't bother
            if (diff > max) {
                // too much, drop a frame
                Log.d(TAG, "diff is " + (diff / 1000000.0) + " ms, max " + (max / 1000000.0) +
                        ", skipping render");
                mRecordedPrevious = false;
                mPreviousWasDropped = true;
                mDroppedFrames++;
                return;
            }

            boolean swapResult;

            if (!mRecordingEnabled || mRecordedPrevious) {
                mRecordedPrevious = false;
                // Render the scene, swap back to front.
                draw();
                swapResult = mWindowSurface.swapBuffers();
            } else {
                mRecordedPrevious = true;

                // recording
                if (mRecordMethod == RECMETHOD_DRAW_TWICE) {
                    //Log.d(TAG, "MODE: draw 2x");

                    // Draw for display, swap.
                    draw();
                    swapResult = mWindowSurface.swapBuffers();

                    // Draw for recording, swap.
                    mVideoEncoder.frameAvailableSoon();
                    mInputWindowSurface.makeCurrent();
                    // If we don't set the scissor rect, the glClear() we use to draw the
                    // light-grey background will draw outside the viewport and muck up our
                    // letterboxing.  Might be better if we disabled the test immediately after
                    // the glClear().  Of course, if we were clearing the frame background to
                    // black it wouldn't matter.
                    //
                    // We do still need to clear the pixels outside the scissor rect, of course,
                    // or we'll get garbage at the edges of the recording.  We can either clear
                    // the whole thing and accept that there will be a lot of overdraw, or we
                    // can issue multiple scissor/clear calls.  Some GPUs may have a special
                    // optimization for zeroing out the color buffer.
                    //
                    // For now, be lazy and zero the whole thing.  At some point we need to
                    // examine the performance here.
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    GLES20.glScissor(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    draw();
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // Restore.
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    mWindowSurface.makeCurrent();

                } else if (mEglCore.getGlVersion() >= 3 &&
                        mRecordMethod == RECMETHOD_BLIT_FRAMEBUFFER) {
                    //Log.d(TAG, "MODE: blitFramebuffer");
                    // Draw the frame, but don't swap it yet.
                    draw();

                    mVideoEncoder.frameAvailableSoon();
                    mInputWindowSurface.makeCurrentReadFrom(mWindowSurface);
                    // Clear the pixels we're not going to overwrite with the blit.  Once again,
                    // this is excessive -- we don't need to clear the entire screen.
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GlUtil.checkGlError("before glBlitFramebuffer");
                    Log.v(TAG, "glBlitFramebuffer: 0,0," + mWindowSurface.getWidth() + "," +
                            mWindowSurface.getHeight() + "  " + mVideoRect.left + "," +
                            mVideoRect.top + "," + mVideoRect.right + "," + mVideoRect.bottom +
                            "  COLOR_BUFFER GL_NEAREST");
                    GLES30.glBlitFramebuffer(
                            0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight(),
                            mVideoRect.left, mVideoRect.top, mVideoRect.right, mVideoRect.bottom,
                            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    int err;
                    if ((err = GLES30.glGetError()) != GLES30.GL_NO_ERROR) {
                        Log.w(TAG, "ERROR: glBlitFramebuffer failed: 0x" +
                                Integer.toHexString(err));
                    }
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // Now swap the display buffer.
                    mWindowSurface.makeCurrent();
                    swapResult = mWindowSurface.swapBuffers();

                } else {
                    //Log.d(TAG, "MODE: offscreen + blit 2x");
                    // Render offscreen.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
                    GlUtil.checkGlError("glBindFramebuffer");
                    draw();

                    // Blit to display.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GlUtil.checkGlError("glBindFramebuffer");
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    swapResult = mWindowSurface.swapBuffers();

                    // Blit to encoder.
                    mVideoEncoder.frameAvailableSoon();
                    mInputWindowSurface.makeCurrent();
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);    // again, only really need to
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);     //  clear pixels outside rect
                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // Restore previous values.
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    mWindowSurface.makeCurrent();
                }
            }

            mPreviousWasDropped = false;

            if (!swapResult) {
                // This can happen if the Activity stops without waiting for us to halt.
                Log.w(TAG, "swapBuffers failed, killing renderer thread");
                shutdown();
                return;
            }

            // Update the FPS counter.
            //
            // Ideally we'd generate something approximate quickly to make the UI look
            // reasonable, then ease into longer sampling periods.
            final int NUM_FRAMES = 120;
            final long ONE_TRILLION = 1000000000000L;
            if (mFpsCountStartNanos == 0) {
                mFpsCountStartNanos = timeStampNanos;
                mFpsCountFrame = 0;
            } else {
                mFpsCountFrame++;
                if (mFpsCountFrame == NUM_FRAMES) {
                    // compute thousands of frames per second
                    long elapsed = timeStampNanos - mFpsCountStartNanos;
                    mActivityHandler.sendFpsUpdate((int)(NUM_FRAMES * ONE_TRILLION / elapsed),
                            mDroppedFrames);

                    // reset
                    mFpsCountStartNanos = timeStampNanos;
                    mFpsCountFrame = 0;
                }
            }
        }

        /**
         * We use the time delta from the previous event to determine how far everything
         * moves.  Ideally this will yield identical animation sequences regardless of
         * the device's actual refresh rate.
         */
        private void update(long timeStampNanos) {
            // Compute time from previous frame.
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                intervalNanos = 0;
            } else {
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    Log.d(TAG, "Time delta too large: " +
                            (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                    intervalNanos = 0;
                }
            }
            mPrevTimeNanos = timeStampNanos;

        }

        /**
         * Draws the scene.
         */
        private void draw() {
            float[] scratch = new float[16];

            // Draw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // Set the camera position (View matrix)
            Matrix.setLookAtM(mViewMatrix, 0, 0, 0, -3, 0f, 0f, 0f, 0f, 1.0f, 0.0f);

            // Calculate the projection and view transformation
            Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);

            // Draw square
            mSquare.draw(mMVPMatrix);

            // Create a rotation for the triangle

            // Use the following code to generate constant rotation.
            // Leave this code out when using TouchEvents.
            // long time = SystemClock.uptimeMillis() % 4000L;
            // float angle = 0.090f * ((int) time);

            Matrix.setRotateM(mRotationMatrix, 0, mAngle, 0, 0, 1.0f);

            // Combine the rotation matrix with the projection and camera view
            // Note that the mMVPMatrix factor *must be first* in order
            // for the matrix multiplication product to be correct.
            Matrix.multiplyMM(scratch, 0, mMVPMatrix, 0, mRotationMatrix, 0);

            // Draw triangle
            mTriangle.draw(scratch);
        }
    }

    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_RECORDING_ENABLED = 3;
        private static final int MSG_RECORD_METHOD = 4;
        private static final int MSG_SHUTDOWN = 5;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface created" message.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                                       int width, int height) {
            // ignore format
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p>
         * Call from UI thread.
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Enable or disable recording.
         * <p>
         * Call from non-UI thread.
         */
        public void setRecordingEnabled(boolean enabled) {
            sendMessage(obtainMessage(MSG_RECORDING_ENABLED, enabled ? 1 : 0, 0));
        }

        /**
         * Set the method used to render a frame for the encoder.
         * <p>
         * Call from non-UI thread.
         */
        public void setRecordMethod(int recordMethod) {
            sendMessage(obtainMessage(MSG_RECORD_METHOD, recordMethod, 0));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_CREATED:
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    long timestamp = (((long) msg.arg1) << 32) |
                            (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_RECORDING_ENABLED:
                    renderThread.setRecordingEnabled(msg.arg1 != 0);
                    break;
                case MSG_RECORD_METHOD:
                    renderThread.setRecordMethod(msg.arg1);
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}