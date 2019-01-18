package net.bradball.motionvideo.customViews

import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Message
import android.text.TextUtils
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.View.OnClickListener
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RelativeLayout
import kotlinx.android.synthetic.main.component_video.view.*
import java.io.IOException
import java.lang.ref.WeakReference
import net.bradball.motionvideo.R



/**
 * Provides video playback. There is nothing directly related to Picture-in-Picture here.
 *
 * Borrowed from the Google Picture-in-Picture mode sample:
 * https://github.com/googlesamples/android-PictureInPicture
 *
 * This is similar to [android.widget.VideoView], but it comes with
 * custom controls (play/pause, fast forward, and fast rewind) that
 * overlay the video.
 */
class ControlledVideoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : RelativeLayout(context, attrs, defStyleAttr) {

    /** Shows the video playback.  */
    private val mSurfaceView: SurfaceView

    // Controls
    private val mToggle: ImageButton
    private val mPipToggle: ImageButton
    private val mCloseButton: ImageButton
    private val mFullScreenButton: ImageButton
    private val mShade: View
    private val mProgressBar: ProgressBar
    private var isFullScreen: Boolean = false

    /** This plays the video. This will be null when no video is set.  */
    private var mMediaPlayer: MediaPlayer? = null
    private var mMediaPlayerErrorListener: MediaPlayer.OnErrorListener? = null

    private var mVideoWidth = 0
    private var mVideoHeight = 0

    private var mCurrentState: Int = 0

    private val videoControlsListener: OnClickListener


    /**
     * The url of the video to play.
     *
     * @return Url of the video.
     */
    var videoUrl: String? = null
        set(url) {
            if (url.equals(videoUrl)) {
                return
            }
            field = url
        }

    /** The title of the video  */
    /**
     * The title of the video to play.
     *
     * @return title of the video.
     */
    var title: String? = null

    /** Should a loaded video start playing automatically or not  */
    var isAutoPlay: Boolean = false

    /** Whether we adjust our view bounds or we fill the remaining area with black bars  */
    private var mAdjustViewBounds: Boolean = false
    /** Whether we account for aspect ratio when AdjustViewBounds is true */
    private var mPreserveAspectRatio: Boolean = false

    /** Handles timeout for media controls.  */
    private var mTimeoutHandler: TimeoutHandler? = null

    /** The listener for all the events we publish.  */
    private var mVideoListener: IVideoListener? = null

    private var mSavedCurrentPosition: Int = 0


    /**
     * Get notified when the video dimensions change, and update the View size accordingly.
     * The video size changes when going from no video, to a video playing, or vice versa.
     */
    private val mSizeChangedListener = MediaPlayer.OnVideoSizeChangedListener { mp, _, _ ->
        mVideoWidth = mp.videoWidth
        mVideoHeight = mp.videoHeight
        requestLayout()
    }

    /**
     * Returns the current position of the video. If the the player has not been created, then
     * assumes the beginning of the video.
     *
     * @return The current position of the video.
     */
    val currentPosition: Int
        get() {
            return if (mMediaPlayer == null) {
                0
            } else mMediaPlayer!!.currentPosition
        }

    val isPlaying: Boolean
        get() = mMediaPlayer != null && mMediaPlayer!!.isPlaying

    /** Monitors all events related to [ControlledVideoView].  */
    interface IVideoListener {

        /** Called when the video is started or resumed.  */
        fun onVideoStarted() {}

        /** Called when the video is paused or finished.  */
        fun onVideoPaused() {}

        /** Called when the video is paused or finished.  */
        fun onVideoStopped() {}

        /** Called when this view should be minimized.  */
        fun onPipToggleClicked() {}

        /** Called when the close button is clicked. */
        fun onVideoCloseClicked() {}

        /** Called when the fullscreen button is clicked. */
        fun onVideoFullScreenClicked(isFullScreen: Boolean) {}
    }


    init {
        setBackgroundColor(Color.BLACK)

        // Inflate the content
        val view = View.inflate(context, R.layout.component_video, this)

        mSurfaceView = view.surface
        mShade = view.shade
        mToggle = view.toggle
        mPipToggle = view.video_toggle_pip
        mCloseButton = view.video_close
        mFullScreenButton = view.video_fullscreen

        // May be Used for Replays. Views will need to be
        // added in layout.
        // --
        //mFastForward = view.fast_forward
        //mFastRewind = view.fast_rewind
        mProgressBar = view.buffering_icon

        val attributes = context.obtainStyledAttributes(
                attrs,
                R.styleable.ControlledVideoView,
                defStyleAttr,
                R.style.Component_ControlledVideoView)
        videoUrl = attributes.getString(R.styleable.ControlledVideoView_android_src)
        setAdjustViewBounds(attributes.getBoolean(R.styleable.ControlledVideoView_android_adjustViewBounds, false))
        title = attributes.getString(R.styleable.ControlledVideoView_android_title)
        isAutoPlay = attributes.getBoolean(R.styleable.ControlledVideoView_autoPlay, true)
        isFullScreen = attributes.getBoolean(R.styleable.ControlledVideoView_isFullScreen, false)
        attributes.recycle()

        mCurrentState = STATE_IDLE

        // Bind view events
        videoControlsListener = OnClickListener { clickedView ->
            when (clickedView.id) {
                R.id.surface -> toggleControls()
                R.id.toggle -> toggle()
                R.id.video_toggle_pip -> handlePipToggle()
                R.id.video_close -> handleCloseButton()
                R.id.video_fullscreen -> handleFullScreenButton()
            }
            // Start or reset the timeout to hide controls
            if (mTimeoutHandler == null) {
                mTimeoutHandler = TimeoutHandler(this@ControlledVideoView)
            }
            mTimeoutHandler!!.removeMessages(TimeoutHandler.MESSAGE_HIDE_CONTROLS)
            if (mMediaPlayer != null && mCurrentState == STATE_PLAYING) {
                mTimeoutHandler!!.sendEmptyMessageDelayed(
                        TimeoutHandler.MESSAGE_HIDE_CONTROLS, TIMEOUT_CONTROLS.toLong())
            }
        }
        mSurfaceView.setOnClickListener(videoControlsListener)
        mToggle.setOnClickListener(videoControlsListener)
        mPipToggle.setOnClickListener(videoControlsListener)
        mCloseButton.setOnClickListener(videoControlsListener)
        mFullScreenButton.setOnClickListener(videoControlsListener)

        // Prepare the surface
        mSurfaceView.holder.apply {
            addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (mMediaPlayer != null) {
                                mMediaPlayer?.setSurface(holder.surface)
                                mSurfaceView.setOnClickListener(videoControlsListener)
                            } else if (isAutoPlay && !TextUtils.isEmpty(videoUrl)) {
                                initializeMediaPlayer(holder.surface)
                            } else {
                                adjustToggleState()
                                showControls()
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {/* noop */}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (mMediaPlayer != null) {
                                mSavedCurrentPosition = mMediaPlayer!!.currentPosition
                            }
                            destroyMediaPlayer()
                        }
                    })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var widthMeasurement = widthMeasureSpec
        var heightMeasurement = heightMeasureSpec
        val width = View.MeasureSpec.getSize(widthMeasurement)
        val widthMode = View.MeasureSpec.getMode(widthMeasurement)
        val height = View.MeasureSpec.getSize(heightMeasurement)
        val heightMode = View.MeasureSpec.getMode(heightMeasurement)
        val aspectRatio: Float
        val videoWidth: Int
        val videoHeight: Int

        if (mVideoWidth > 0 && mVideoHeight > 0) {
            videoWidth = mVideoWidth
            videoHeight = mVideoHeight
            aspectRatio = videoHeight.toFloat() / videoWidth.toFloat()
        } else {
            aspectRatio = 9F / 16F
            videoWidth = width
            videoHeight = (width * aspectRatio).toInt()
        }

        val viewRatio = height.toFloat() / width

        if (videoWidth != 0 && videoHeight != 0) {
            var needsPadding = false

            if (mAdjustViewBounds) {

                var targetHeight: Float? = null
                var targetWidth: Float? = null

                if (widthMode == View.MeasureSpec.EXACTLY && heightMode != View.MeasureSpec.EXACTLY) {
                    targetHeight = (width * aspectRatio)
                } else if ((widthMode != View.MeasureSpec.EXACTLY && heightMode == View.MeasureSpec.EXACTLY)) {
                    targetWidth = (height / aspectRatio)
                } else {
                    targetHeight = (width * aspectRatio)
                }

                if (targetHeight != null) {
                    // When we are looking to preserve the aspect ratio and our
                    // target height spills over available height, we just
                    // want the width/height to be the full container and let
                    // calculated padding handle retaining aspect ratio
                    if (mPreserveAspectRatio && targetHeight > height) {
                        needsPadding = true
                    } else {
                        heightMeasurement = View.MeasureSpec.makeMeasureSpec(
                                targetHeight.toInt(), View.MeasureSpec.EXACTLY)
                    }
                }

                if (targetWidth != null) {
                    // When we are looking to preserve the aspect ratio and our
                    // target height spills over available height, we just
                    // want the width/height to be the full container and let
                    // calculated padding handle retaining aspect ratio
                    if (mPreserveAspectRatio && targetWidth > width) {
                        needsPadding = true
                    } else {
                        widthMeasurement = View.MeasureSpec.makeMeasureSpec(
                                targetWidth.toInt(), View.MeasureSpec.EXACTLY)
                    }
                }
            } else {
                needsPadding = true
            }

            if (needsPadding) {
                // Make sure our Measurmements are Set to the Height/Width
                // of the current Screen Resolution EXACTLY.
                widthMeasurement = View.MeasureSpec.makeMeasureSpec(
                        width, View.MeasureSpec.EXACTLY)
                heightMeasurement = View.MeasureSpec.makeMeasureSpec(
                        height, View.MeasureSpec.EXACTLY)

                if (aspectRatio > viewRatio) {
                    val padding = ((width - height / aspectRatio) / 2).toInt()
                    setPadding(padding, 0, padding, 0)
                } else {
                    val padding = ((height - width * aspectRatio) / 2).toInt()
                    setPadding(0, padding, 0, padding)
                }
            } else if (paddingTop > 0 || paddingRight > 0 || paddingBottom > 0 || paddingLeft > 0) {
                setPadding(0, 0, 0, 0)
            }
        }

        super.onMeasure(widthMeasurement, heightMeasurement)
    }

    override fun onDetachedFromWindow() {
        if (mTimeoutHandler != null) {
            mTimeoutHandler!!.removeMessages(TimeoutHandler.MESSAGE_HIDE_CONTROLS)
            mTimeoutHandler = null
        }
        super.onDetachedFromWindow()
    }


    fun setFullScreenMode(enabled: Boolean) {
        isFullScreen = enabled
        updateFullScreenToggleButton()

        // If we're in fullscreen, hide the pip toggle.
        // If we're not, set it's visible to the same as the other controls.
        mPipToggle.visibility = when (enabled) {
            true -> View.INVISIBLE
            false -> mToggle.visibility
        }
    }

    /**
     * Sets the listener to monitor movie events.
     *
     * @param videoListener The listener to be set.
     */
    fun setVideoListener(videoListener: IVideoListener?) {
        mVideoListener = videoListener
    }

    fun setPreserveAspectRatio(preserveAspectRatio: Boolean) {
        if (mPreserveAspectRatio == preserveAspectRatio) {
            return
        }

        mPreserveAspectRatio = preserveAspectRatio
        if (preserveAspectRatio) {
            setBackgroundColor(Color.BLACK)
        } else {
            background = null
        }
    }

    fun setAdjustViewBounds(adjustViewBounds: Boolean) {
        if (mAdjustViewBounds == adjustViewBounds) {
            return
        }
        mAdjustViewBounds = adjustViewBounds
        if (adjustViewBounds) {
            background = null
        } else {
            setBackgroundColor(Color.BLACK)
        }
        requestLayout()
    }

    private fun updateFullScreenToggleButton() {
        val iconId = when (isFullScreen) {
            true -> R.drawable.ic_fullscreen_exit
            false -> R.drawable.ic_fullscreen
        }
        mFullScreenButton.setImageDrawable(context.getDrawable(iconId))
        mFullScreenButton.visibility = mToggle.visibility
    }

    /** Shows all the controls.  */
    fun showControls() {
        TransitionManager.beginDelayedTransition(this)
        mShade.visibility = View.VISIBLE
        mToggle.visibility = View.VISIBLE
        if (!isFullScreen) {
            mPipToggle.visibility = View.VISIBLE
        }
        mCloseButton.visibility = View.VISIBLE

        mFullScreenButton.visibility = View.VISIBLE
    }

    /** Hides all the controls.  */
    fun hideControls() {
        TransitionManager.beginDelayedTransition(this.video_controls_container)
        mShade.visibility = View.INVISIBLE
        mToggle.visibility = View.INVISIBLE
        mPipToggle.visibility = View.INVISIBLE
        mCloseButton.visibility = View.INVISIBLE
        mFullScreenButton.visibility = View.INVISIBLE
    }

    /** Fast-forward the video.  */
    fun fastForward() {
        if (mMediaPlayer == null) {
            return
        }
        showBufferingIcon()
        mMediaPlayer!!.seekTo(mMediaPlayer!!.currentPosition + FAST_FORWARD_REWIND_INTERVAL)
    }

    /** Fast-rewind the video.  */
    fun fastRewind() {
        if (mMediaPlayer == null) {
            return
        }
        showBufferingIcon()
        mMediaPlayer!!.seekTo(mMediaPlayer!!.currentPosition - FAST_FORWARD_REWIND_INTERVAL)
    }

    fun play() {
        if (mMediaPlayer == null) {
            val surface = mSurfaceView.holder.surface
            if (surface != null && surface.isValid) {
                initializeMediaPlayer(surface)
            }
        } else if (mCurrentState != STATE_BUFFERING) {
            mMediaPlayer!!.start()
            mCurrentState = STATE_PLAYING
            adjustToggleState()
            keepScreenOn = true
            if (mVideoListener != null) {
                mVideoListener!!.onVideoStarted()
            }
        }
    }

    fun pause() {
        if (mMediaPlayer == null) {
            adjustToggleState()
            return
        }

        // If we Are not still preparing, pause the media Player
        // if we are preparing, the onPreparedListener will pause
        // the player for us.
        if (mCurrentState != STATE_BUFFERING) {
            mMediaPlayer!!.pause()
        }

        mCurrentState = STATE_PAUSED
        adjustToggleState()
        keepScreenOn = false
        if (mVideoListener != null) {
            mVideoListener!!.onVideoPaused()
        }
    }

    fun stop() {
        if (mMediaPlayer != null) {
            if (mMediaPlayer!!.isPlaying && mCurrentState != STATE_BUFFERING) {
                mMediaPlayer!!.stop()
            }
            destroyMediaPlayer()
            adjustToggleState()
        }
    }

    private fun initializeMediaPlayer(surface: Surface) {
        if (TextUtils.isEmpty(videoUrl)) {
            return
        }

        mMediaPlayer = MediaPlayer()
        mMediaPlayer!!.setSurface(surface)
        mMediaPlayer!!.setOnVideoSizeChangedListener(mSizeChangedListener)
        mMediaPlayer!!.setOnSeekCompleteListener { hideBufferingIcon() }
        if (mMediaPlayerErrorListener != null) {
            mMediaPlayer!!.setOnErrorListener(mMediaPlayerErrorListener)
        }
        startVideo()
    }

    fun setOnVideoErrorListener(listener: MediaPlayer.OnErrorListener) {
        mMediaPlayerErrorListener = listener
        if (mMediaPlayer != null) {
            mMediaPlayer?.setOnErrorListener(listener)
        }
    }


    /** Restarts playback of the video.  */
    private fun startVideo() {
        mMediaPlayer!!.reset()
        try {
            mMediaPlayer!!.setDataSource(context, Uri.parse(videoUrl))
            mMediaPlayer!!.setOnPreparedListener { mediaPlayer ->

                val latestState = mCurrentState
                mCurrentState = STATE_BUFFERED

                when (latestState) {
                    // We were started, and now we are ready to play media.
                    STATE_BUFFERING -> {
                        // Adjust the aspect ratio of this view
                        mVideoWidth = mediaPlayer.videoWidth
                        mVideoHeight = mediaPlayer.videoHeight

                        // The video may or may not have a size yet.
                        // If it does, go ahead and do a layout.
                        // If it doesn't, the SizeChanged listener will handle it.
                        if (mVideoWidth > 0 && mVideoHeight > 0) {
                            requestLayout()
                        }

                        hideBufferingIcon()
                        if (mSavedCurrentPosition > 0) {
                            mediaPlayer.seekTo(mSavedCurrentPosition)
                            mSavedCurrentPosition = 0
                        } else {
                            // Start automatically
                            play()
                        }
                    }
                    // We have been paused while it was buffering.
                    STATE_PAUSED -> mediaPlayer.pause()
                    // We have been stopped or encountered an error. Shut it down!
                    else -> stop()
                }
            }
            mMediaPlayer!!.setOnCompletionListener {
                adjustToggleState()
                keepScreenOn = false
                if (mVideoListener != null) {
                    mVideoListener!!.onVideoStopped()
                }
            }
            mCurrentState = STATE_BUFFERING
            showBufferingIcon()
            mMediaPlayer!!.prepareAsync()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open video", e)
        }

    }


    private fun destroyMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer!!.release()
            mMediaPlayer = null
            mCurrentState = STATE_IDLE
            mVideoListener?.onVideoStopped()
        }
    }

    internal fun toggle() {
        if (mMediaPlayer == null || !mMediaPlayer!!.isPlaying) {
            play()
        } else {
            pause()
        }
    }

    private fun toggleControls() {
        if (mShade.visibility == View.VISIBLE) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun handlePipToggle() {
        hideControls()
        mVideoListener?.onPipToggleClicked()
    }

    private fun handleCloseButton() {
        hideControls()
        mVideoListener?.onVideoCloseClicked()
    }

    private fun handleFullScreenButton() {
        hideControls()
        mVideoListener?.onVideoFullScreenClicked(isFullScreen)
    }

    private fun showBufferingIcon() {
        hideControls()
        mProgressBar.visibility = View.VISIBLE
    }

    private fun hideBufferingIcon() {
        mProgressBar.visibility = View.GONE
    }

    internal fun adjustToggleState() {
        if (mCurrentState == STATE_PLAYING) {
            mToggle.contentDescription = resources.getString(R.string.pause)
            mToggle.setImageResource(R.drawable.ic_pause)
        } else {
            mToggle.contentDescription = resources.getString(R.string.play)
            mToggle.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    private class TimeoutHandler internal constructor(view: ControlledVideoView) : Handler() {

        private val mMovieViewRef: WeakReference<ControlledVideoView> = WeakReference(view)

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_HIDE_CONTROLS -> {
                    val movieView = mMovieViewRef.get()
                    movieView?.hideControls()
                }
                else -> super.handleMessage(msg)
            }
        }

        companion object {

            internal const val MESSAGE_HIDE_CONTROLS = 1
        }
    }

    companion object {

        private const val TAG = "ControlledVideoView"

        // all possible internal states
        private const val STATE_ERROR = -1
        private const val STATE_IDLE = 0
        private const val STATE_BUFFERING = 1
        private const val STATE_BUFFERED = 2
        private const val STATE_PLAYING = 3
        private const val STATE_PAUSED = 4
        private const val STATE_PLAYBACK_COMPLETED = 5

        /** The amount of time we are stepping forward or backward for fast-forward and fast-rewind.  */
        private const val FAST_FORWARD_REWIND_INTERVAL = 5000 // ms

        /** The amount of time until we fade out the controls.  */
        private const val TIMEOUT_CONTROLS = 3000 // ms

        /**
         * Utility to return a default size. Uses the supplied size if the
         * MeasureSpec imposed no constraints. Will get larger if allowed
         * by the MeasureSpec.
         *
         * @param size Default size for this view
         * @param measureSpec Constraints imposed by the parent
         * @return The size this view should be.
         */
        fun getDefaultSize(size: Int, measureSpec: Int): Int {
            var result = size
            val specMode = View.MeasureSpec.getMode(measureSpec)
            val specSize = View.MeasureSpec.getSize(measureSpec)

            when (specMode) {
                View.MeasureSpec.UNSPECIFIED -> result = size
                View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY -> result = specSize
            }
            return result
        }
    }

}
