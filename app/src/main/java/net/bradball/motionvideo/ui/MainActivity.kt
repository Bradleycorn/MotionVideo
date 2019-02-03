package net.bradball.motionvideo.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.viewpager.widget.ViewPager
import kotlinx.android.synthetic.main.activity_main.*
import net.bradball.motionvideo.customViews.ControlledVideoView
import net.bradball.motionvideo.R
import net.bradball.motionvideo.ui.list.ListFragment

class MainActivity : AppCompatActivity() {

    private val viewModel: MainActivityViewModel by viewModels()

    /**
     * Keep track of the current playback and layout states, so the view
     * can take action only when they change.
     */
    private var currentPlaybackState: MainActivityViewModel.VideoPlaybackState? = null
    private var currentLayoutState: MainActivityViewModel.VideoLayoutState? = null

    /**
     * A listener to allow us to react to physical device orientation changes.
     */
    lateinit var orientationListener: OrientationEventListener

    private val videoPlayerCallbacks = object: ControlledVideoView.IVideoListener {
        override fun onPipToggleClicked() {
            viewModel.onPipToggled()
        }

        override fun onVideoCloseClicked() {
            viewModel.onVideoClosed()
        }

        override fun onVideoFullScreenClicked(isFullScreen: Boolean) {
            //isFullScreen = true means we're already in fullscreen,
            // so we want to turn it off and lock to portrait. And Vice Versa
            val orientationLock = when (isFullScreen) {
                true -> Configuration.ORIENTATION_PORTRAIT
                false -> Configuration.ORIENTATION_LANDSCAPE
            }

            viewModel.onOrientationForced(orientationLock)
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.app_name)

        setupViewPager(list_view_pager)
        list_tabs.setupWithViewPager(list_view_pager)

        setupVideo()
    }

    override fun onPause() {
        super.onPause()
        if (currentPlaybackState == MainActivityViewModel.VideoPlaybackState.PLAYING)  {
            video_player.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        if (currentPlaybackState == MainActivityViewModel.VideoPlaybackState.PLAYING) {
            video_player.ensureSurface()
            video_player.showControls()
            attachVideoListeners()
        }
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.options_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        super.onPrepareOptionsMenu(menu)
        val playVideoItem = menu?.findItem(R.id.option_play_video)
        val stopVideoItem = menu?.findItem(R.id.option_stop_video)

        playVideoItem?.isVisible = currentPlaybackState == MainActivityViewModel.VideoPlaybackState.STOPPED
        stopVideoItem?.isVisible = currentPlaybackState == MainActivityViewModel.VideoPlaybackState.PLAYING

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.option_play_video -> {
                viewModel.onVideoToggled()
                true
            }
            R.id.option_stop_video -> {
                viewModel.onVideoToggled()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    /**
     * Called by the system when the device configuration changes.
     * Turn on or off all of the android window decoration STUFF:
     *   Toggle the action bar,
     *   (Un)Lock the nav drawer,
     *   Toggle window full screen flags
     *
     *   Note that this method DOES NOT DO ANYTHING with the video
     *   or the video view itself. It's only responsible for toggling
     *   on/off the decor.
     *
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            supportActionBar?.hide()
            supportActionBar?.setDisplayShowTitleEnabled(false)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        } else { // turn OFF full screen
            supportActionBar?.show()
            supportActionBar?.setDisplayShowTitleEnabled(true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        }
    }


    private fun setupViewPager(viewPager: ViewPager) {
        val adapter = ViewPagerAdapter(supportFragmentManager)

        viewModel.pages.forEach { pageTitle ->
            adapter.addFragment(ListFragment.newInstance(pageTitle), pageTitle)
        }

        viewPager.adapter = adapter
    }


    /**
     * Setup the video player (and the whole system for toggling playback states, layout states,
     * etc).
     */
    private fun setupVideo() {

        /*
         * Setup an Orientation listener so that this fragment can react
         * to changes in the physical orienation of the device
         */
        orientationListener = object: OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                viewModel.onOrientationChange(orientation, resources.configuration.orientation)
            }
        }.apply { disable() }

        // Set the video URL
        video_player.videoUrl = viewModel.videoUrl


        /*
         * The major worker here...
         *
         * Watch for changes in playbackstate and/or layoutstate and handle them appropriately.
         * Start/Or stop the player, Update the layout between embedded, pip, and fullscreen mode.
         */
        viewModel.playbackState.observe(this, Observer { playbackState ->
            if (playbackState != currentPlaybackState) {
                invalidateOptionsMenu() // update the play/stop icon in the options menu.

                currentPlaybackState = playbackState

                when (currentPlaybackState) {
                    MainActivityViewModel.VideoPlaybackState.PLAYING -> {
                        if (orientationListener.canDetectOrientation()) {
                            orientationListener.enable()
                        }
                        playVideo()
                    }
                    else -> {
                        orientationListener.disable()
                        stopVideo()
                    }
                }
            }

            updateVideoLayout()
        })


        viewModel.layoutState.observe(this, Observer { layoutState ->
            if (layoutState != currentLayoutState) {
                currentLayoutState = layoutState
                updateVideoLayout()
            }
        })
    }



    /**
     * This method is responsible for switching the layout to the correct state, which is
     * one of: EMBEDDED, PIP, OR FULLSCREEN.
     *
     * There are several things accomplished:
     * 1. Toggle the constraints on the various views in this layout to put the
     *    video into the correct layout position.
     * 2. Set the orientaiton on the activity appropriately for the current layout state,
     *    which will trigger a configuration change (see onConfigurationChanged() above).
     * 3. Enable/Disable dragging of the video appropriately.
     *
     * NOTE: You probably don't want to call this method directly. Instead, call methods
     * on the viewModel that will trigger an update to the current layoutstate.
     */
    private fun updateVideoLayout() {
        val isFullscreen = (currentLayoutState == MainActivityViewModel.VideoLayoutState.FULLSCREEN)

        // Set the activity's orientation if it needs to be changed.
        // Doing this will trigger a configuration change, and we'll
        // handle switching around window items (show/hide actionbar, etc)
        // in onConfigurationChange() (see below).
        if (isFullscreen && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (!isFullscreen && resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        // In addition to changing the window items, we also need to
        // change the video itself, so let's do that.
        video_player.setFullScreenMode(isFullscreen)
        video_player.setPreserveAspectRatio(isFullscreen)

        val videoState = Pair(currentPlaybackState, currentLayoutState)

        // Note: Stopped, Fullscreen is impossible, so we let the else handle it.
        val newState = when (videoState) {
            Pair(MainActivityViewModel.VideoPlaybackState.PLAYING, MainActivityViewModel.VideoLayoutState.FULLSCREEN) -> R.id.video_state_fullscreen
            Pair(MainActivityViewModel.VideoPlaybackState.PLAYING, MainActivityViewModel.VideoLayoutState.PIP) -> R.id.video_state_pip_playing
            Pair(MainActivityViewModel.VideoPlaybackState.STOPPED, MainActivityViewModel.VideoLayoutState.PIP) -> R.id.video_state_pip_stopped
            Pair(MainActivityViewModel.VideoPlaybackState.PLAYING, MainActivityViewModel.VideoLayoutState.EMBEDDED) -> R.id.video_state_embedded_playing
            else -> R.id.video_state_embedded_stopped
        }

        if (activity_container.currentState != newState) {
            activity_container.transitionToState(newState)
        }
    }


    private fun playVideo() {
        video_player.play()
        attachVideoListeners()
    }

    private fun stopVideo() {
        if (video_player.isPlaying) {
            video_player.stop()
            video_player.setVideoListener(null)
        }
    }

    private fun attachVideoListeners() {
        video_player.setVideoListener(videoPlayerCallbacks)
    }

}
