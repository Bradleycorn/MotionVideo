package net.bradball.motionvideo.ui

import android.content.res.Configuration
import android.os.Handler
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel

class MainActivityViewModel: ViewModel() {

    /**
     * Setup some data for our ViewPager tabs
     */
    val pages = listOf("First", "Middle", "Last")

    /**
     * The url that our video will use
     */
    val videoUrl = "https://video-dev.github.io/streams/x36xhzz/x36xhzz.m3u8"

    /**
     * An enum to help us keep track of whether the video is playing or stopped.
     *
     * This could be a simple boolean, but we may want to know about additional
     * states as well, like paused.
     */
    enum class VideoPlaybackState {
        STOPPED,
        PLAYING
    }

    /**
     * An enum to help us keep track of the current layout of the video player.
     */
    enum class VideoLayoutState {
        EMBEDDED,
        FULLSCREEN,
        PIP
    }

    /**
     * When returning from FullScreen, we need to know if we should go to
     * Picture-in-Picture (PIP) mode, or embedded mode. This flag will
     * keep track of that.
     */
    private var showInPipMode = false

    /**
     * A LiveData that emits to observers whenever the video playback state changes
     */
    private val _playbackState = MutableLiveData<VideoPlaybackState>().apply {
        value = VideoPlaybackState.STOPPED
    }
    val playbackState: LiveData<VideoPlaybackState> = _playbackState


    /**
     * A LiveData that emits to observers whenever the video player's layout state changes
     */
    private val _layoutState = MutableLiveData<VideoLayoutState>().apply {
        value = VideoLayoutState.EMBEDDED
    }
    val layoutState: LiveData<VideoLayoutState> = Transformations.map(_layoutState) { newState ->
        showInPipMode = when (newState) {
            VideoLayoutState.PIP -> true
            VideoLayoutState.EMBEDDED -> false
            else -> showInPipMode
        }

        return@map newState
    }

    /**
     * A handler that we'll use to effectively debounce orientation changes
     * so that we don't frantically try to rotate the screen back and forth
     * if the orientation changes really fast. When the orientation changes,
     * well post delayed runnables to this handler to change the video
     * orientation after a short delay.
     */
    private val orientationHandler = Handler()

    /**
     * A holder for the device's current physical orientation. Set by the
     * orientation handler, and facilitates debouncing orientation changes.
     */
    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    /**
     * A lock that is set when the "Full Screen" button is clicked,
     * since in that case, we need to "lock" the device to a particular
     * orientation, and (temporarily) ignore rotation.
     * When set, the lock will contain the configuration that the
     * device is currently locked to.
     * The lock will be set when the full screen button is pressed, and released
     * when the device is rotated to the lock's configuration value.
     */
    private var orientationLock: Int = Configuration.ORIENTATION_UNDEFINED


    /**
     * Handle orientation changes. As the device is rotated, new orientaion
     * values wil be generated in the range from 0-360. We don't want to make the user
     * rotate to EXACTLY 90 or 180 degrees to trigger fullscreen mode toggles. Instead,
     * we want to provide a comfortable range. Also, we'll debounce orientation changes,
     * so that our views won't frantically try to toggle back and forth if the user decides
     * to swith the orientation really fast.
     */
    fun onOrientationChange(deviceOrientation: Int, activityOrientation: Int?) {
        currentOrientation = when (deviceOrientation) {
            in 0..20, in 160..200, in 340..359 -> { // Portrait
                Configuration.ORIENTATION_PORTRAIT

            }

            in 70..110, in 250..290 -> { // Landscape
                Configuration.ORIENTATION_LANDSCAPE
            }

            else -> currentOrientation // In between, keep the current orientation
        }

        // we need to clear the orientation lock if the
        // device is rotated to the same orientation as the lock,
        // so that we'll properly allow orientation to change
        // later when the device is rotated back.
        if (currentOrientation == orientationLock) {
            orientationLock = Configuration.ORIENTATION_UNDEFINED
        }

        if (orientationLock == Configuration.ORIENTATION_UNDEFINED
                && activityOrientation != currentOrientation) {

            //Post a message to set the orientation
            orientationHandler.removeCallbacksAndMessages(null)
            orientationHandler.postDelayed({ setOrientation() }, 50)
        }
    }

    fun onOrientationForced(orientation: Int) {
        orientationLock = orientation
        setOrientation(orientation)
    }

    fun onVideoToggled() {
        val newState = when (_playbackState.value) {
            VideoPlaybackState.STOPPED -> VideoPlaybackState.PLAYING
            else -> VideoPlaybackState.STOPPED
        }

        _playbackState.value = newState
    }

    fun onVideoClosed() {
        if (_layoutState.value == VideoLayoutState.FULLSCREEN) {
            _layoutState.value = if (showInPipMode) VideoLayoutState.PIP else VideoLayoutState.EMBEDDED
        }

        _playbackState.value = VideoPlaybackState.STOPPED
    }

    fun onPipToggled() {
        _layoutState.value = when (_layoutState.value) {
            VideoLayoutState.PIP -> VideoLayoutState.EMBEDDED
            else -> VideoLayoutState.PIP
        }
    }

    private fun setOrientation(requestedOrientation: Int = -1) {

        val newOrientation = requestedOrientation.takeUnless { it == -1 } ?: currentOrientation

        _layoutState.value = when (newOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> VideoLayoutState.FULLSCREEN
            else -> {
                if (showInPipMode) {
                    VideoLayoutState.PIP
                } else {
                    VideoLayoutState.EMBEDDED
                }
            }
        }
    }

}

