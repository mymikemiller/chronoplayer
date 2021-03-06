package com.mymikemiller.ytaggregator

import android.app.Activity
import android.content.*
import com.google.android.youtube.player.YouTubePlayer
import com.google.android.youtube.player.YouTubePlayerView

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.*
import android.os.Handler
import android.preference.PreferenceManager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import com.sothree.slidinguppanel.SlidingUpPanelLayout
import android.view.inputmethod.InputMethodManager
import android.support.v4.content.LocalBroadcastManager
import android.support.v4.view.ViewPager
import android.content.Intent
import com.mymikemiller.ytaggregator.util.*
import android.content.IntentFilter
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import com.google.api.client.util.DateTime
import kotlinx.android.synthetic.main.activity_main.*

/**
 * A video player allowing users to watch aggregated YouTube episodes in chronological order and
 * commit the resulting playlist to YouTube.
 */
class MainActivity : YouTubeFailureRecoveryActivity(),
        YouTubePlayer.OnFullscreenListener {

    val TAG = "Aggregator"

    val WATCH_HISTORY_REQUEST = 1  // The request code from the WatchHistoryActivity activity
    val CHANNEL_SELECT_REQUEST = 2  // The request code from the ChannelSelectActivity activity
    val MANAGE_CHANNELS_REQUEST = 3  // The request code from the ManageChannelsActivity activity

    //region [Variable definitions]
    private lateinit var mPlaylistTitle: String
    private lateinit var baseLayout: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var slidingLayout: SlidingUpPanelLayout
    private lateinit var playerView: YouTubePlayerView
    private lateinit var player: YouTubePlayer
    private lateinit var otherViews: View
    private lateinit var fetchVideosProgressSection: LinearLayout
    private lateinit var gatheringTextView: TextView
    private lateinit var fetchVideosProgresBar: ProgressBar
    private lateinit var fetchVideosProgresText: TextView
    private var fullscreen: Boolean = false
    private lateinit var playerStateChangeListener: MyPlayerStateChangeListener
    private lateinit var playbackEventListener: MyPlaybackEventListener
    private var mCurrentlyPlayingVideoDetail: Detail? = null
    val recordCurrentTimeHandler: Handler = Handler()
    private lateinit var mPlaylistRecyclerView: RecyclerView
    private lateinit var mLinearLayoutManager: LinearLayoutManager
    private var mAdapter: RecyclerAdapter = RecyclerAdapter(this, listOf(), { true }, { true }, null)
    private lateinit var mUpButton: ImageView
    private lateinit var mDownButton: ImageView
    private lateinit var mTargetButton: ImageView
    private lateinit var mSearchEditText: EditText
    private lateinit var mExpandButton: ImageView
    private lateinit var mPreferencesButton: ImageView
    private var mPlayerInitialized: Boolean = false
    private var mAdapterInitialized: Boolean = false
    private lateinit var mBroadcastReceiver: BroadcastReceiver
    private lateinit var mEpisodePager: ViewPager
    private lateinit var mEpisodeViewPagerAdapter: EpisodePagerAdapter
    private lateinit var mNoVideosDueToRemovedTextView: TextView
    private lateinit var mNoVideosDueToNoChannelsTextView: TextView
    private var mNumVideosToFetch = 0
    private var mNumVideosFetched = 0

    // These collections include the removed videos
    var mDetailsByDateIncludingRemoved = listOf<Detail>()

    // These collections have the removed videos filtered out
    var mDetailsByDate = listOf<Detail>()

    // endregion


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //region [Variable initialization]
        baseLayout = findViewById<LinearLayout>(R.id.layout)
        bar = findViewById<LinearLayout>(R.id.bar)
        slidingLayout = findViewById(R.id.sliding_layout)
        playerView = findViewById<YouTubePlayerView>(R.id.player)
        otherViews = findViewById(R.id.other_views)
        fetchVideosProgressSection = findViewById(R.id.fetchVideosProgressSection)
        gatheringTextView = findViewById(R.id.gatheringTextView)
        fetchVideosProgresBar = findViewById(R.id.fetchVideosProgressBar)
        fetchVideosProgresText = findViewById(R.id.fetchVideosProgressText)
        playerStateChangeListener = MyPlayerStateChangeListener(playNextVideo)
        playbackEventListener = MyPlaybackEventListener(recordCurrentTime, recordCurrentTimeHandler)
        mUpButton = findViewById(R.id.up_button)
        mDownButton = findViewById(R.id.down_button)
        mTargetButton = findViewById(R.id.target_button)
        mSearchEditText = findViewById(R.id.searchEditText)
        mExpandButton = findViewById(R.id.expand_button)
        mPreferencesButton = findViewById(R.id.preferences_button)
        mPlaylistRecyclerView = findViewById(R.id.playlist)
        mLinearLayoutManager = LinearLayoutManager(this)
        mEpisodePager = findViewById(R.id.episodeViewPager)
        // endregion

        // Run all the setup functions
        setUp(intent)

        // Register for broadcast intents from settings
        val filter = IntentFilter(PreferencesActivity.MANAGE_CHANNELS)
        filter.addAction(PreferencesActivity.CHANGE_PLAYLIST_TITLE)
        filter.addAction(PreferencesActivity.WATCH_HISTORY)
        filter.addAction(PreferencesActivity.SHOW_ALL)
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(mBroadcastReceiver, filter)
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent != null) {
            setUp(newIntent)
        }
    }

    fun setUp(theIntent: Intent) {
        if (theIntent.hasExtra(getString(R.string.extraLaunchPlaylistTitle))) {
            mPlaylistTitle = theIntent.getStringExtra(getString(R.string.extraLaunchPlaylistTitle))
        } else {
            // Look in shared preferences to see if we've chosen a playlist title, otherwise use the default
            mPlaylistTitle = getLaunchPlaylistTitle()
        }

        // Save the launch channel to sharedPreferences so we start there next time
        saveLaunchPlaylistTitle(mPlaylistTitle)

        setUpNoVideosWarnings()
        setUpYouTubeFetch()
        setUpPlayer()
        setUpEpisodePager()
        setUpPlaylist()
        setUpSearch()
        setUpPreferences()
    }

    private fun saveLaunchPlaylistTitle(playlistTitle: String) {
        // Store the channel in shared preferences so we can go right to MainAcivity when we start up next time
        val preferences = getSharedPreferences(getString(R.string.sharedPrefsName), Context.MODE_PRIVATE)
        val editor = preferences.edit()
        editor.putString(getString(R.string.prefPlaylistTitle), playlistTitle)
        editor.apply()
    }

    private fun getLaunchPlaylistTitle(): String {
        // If we have a stored preference for the last playlist we opened, open that one. OTherwise use the default.
        val preferences = getSharedPreferences(getString(R.string.sharedPrefsName), Context.MODE_PRIVATE)
        val playlistTitle = preferences.getString(getString(R.string.prefPlaylistTitle), getString(R.string.defaultPlaylistTitle))
        return playlistTitle
    }

    protected fun makeLinkClickable(strBuilder: SpannableStringBuilder, span: URLSpan) {
        val start = strBuilder.getSpanStart(span);
        val end = strBuilder.getSpanEnd(span);
        val flags = strBuilder.getSpanFlags(span);
        val clickable: ClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View?) {
                when (span.url) {
                    "showAll" -> showAllVideos()
                    "manageChannels" -> {
                        val intent = Intent()
                        intent.action = PreferencesActivity.MANAGE_CHANNELS
                        LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)
                    }
                }

            }
        }
        strBuilder.setSpan(clickable, start, end, flags);
        strBuilder.removeSpan(span);
    }

    private fun setUpNoVideosWarnings() {
        // Set up the "no videos" text with a link to show all videos
        mNoVideosDueToRemovedTextView = findViewById(R.id.noVideosWarningDueToRemoved)
        var html = getString(R.string.noVideosWarningDueToRemoved) + " <a href=\"showAll\">" + getString(R.string.showAllVideosLink) + "</a>"

        // Make the links in the string clickable. They're handled in makeLinkClickable()
        var sequence = Html.fromHtml(html);
        var strBuilder = SpannableStringBuilder(sequence);
        var urls = strBuilder.getSpans(0, sequence.length, URLSpan::class.java)
        for (span in urls) {
            makeLinkClickable(strBuilder, span)
        }
        mNoVideosDueToRemovedTextView.setText(strBuilder)
        mNoVideosDueToRemovedTextView.setMovementMethod(LinkMovementMethod.getInstance())

        // Set up the "no videos" text with a link to the Manage Channels page
        mNoVideosDueToNoChannelsTextView = findViewById(R.id.noVideosWarningDueToNoChannels)
        html = getString(R.string.noVideosWarningDueToNoChannels) + " <a href=\"manageChannels\">" + getString(R.string.manageChannelsLink) + "</a>"
        sequence = Html.fromHtml(html);
        strBuilder = SpannableStringBuilder(sequence);
        urls = strBuilder.getSpans(0, sequence.length, URLSpan::class.java)
        for (span in urls) {
            makeLinkClickable(strBuilder, span)
        }
        mNoVideosDueToNoChannelsTextView.setText(strBuilder)
        mNoVideosDueToNoChannelsTextView.setMovementMethod(LinkMovementMethod.getInstance())

        setNoVideosWarningsVisibility()
    }

    private fun setNoVideosWarningsVisibility() {
        // Show the "no videos due to everything being removed" message if appropriate
        if (mDetailsByDate.size == 0 && mDetailsByDateIncludingRemoved.size > 0) {
            mNoVideosDueToRemovedTextView.visibility = View.VISIBLE
        } else {
            mNoVideosDueToRemovedTextView.visibility = View.GONE
        }

        // Show the "no videos due to no channels" message if appropriate
        if (PlaylistChannels.getChannels(this, mPlaylistTitle).size == 0) {
            mNoVideosDueToNoChannelsTextView.visibility = View.VISIBLE
        } else {
            mNoVideosDueToNoChannelsTextView.visibility = View.GONE
        }

        refreshEpisodePagerVisibility()
    }

    private fun setUpYouTubeFetch() {
        // Specify no channels, which means we will fetch them all
        loadPlaylist(listOf())
    }

    // Send in listOf() to fetch all
    fun loadPlaylist(addedChannelNames: List<String>) {
        // First clear the playlist so we don't try playing something while the fetch happens
        updateAdapters(listOf())

        // Make sure the playlist is minimized so we can see the progress
        slidingLayout.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED

        // Show the fetch progress section
        fetchVideosProgressSection.visibility = View.VISIBLE
        gatheringTextView.text = getString(R.string.preparingToGather)
        fetchVideosProgresBar.setProgress(0)
        fetchVideosProgressText.setText("")

        // Get the details ordered by date uploaded and force an upgrade if necessary
        val detailsFromDbByDate = PlaylistManipulator.orderByDate(VideoList.getAllDetailsFromDb(this,
                mPlaylistTitle))

        val stopAtDate = if (detailsFromDbByDate.isEmpty()) null else detailsFromDbByDate[detailsFromDbByDate.size - 1].dateUploaded

        val channels = PlaylistChannels.getChannels(this, mPlaylistTitle)

        // Fetch the required channels
        VideoList.getNumDetailsToFetch(this, channels, { numDetailsFromYouTube ->
            mNumVideosToFetch = numDetailsFromYouTube
            fetchVideosProgresBar.max = mNumVideosToFetch
            fetchVideosProgresBar.setProgress(0)
            mNumVideosFetched = 0
            VideoList.fetchAllDetails(this,
                    channels,
                    addedChannelNames, stopAtDate, respondToIncrementalVideosFetched, detailsFetched)
        })

    }

    // This happens once the details are fetched from YouTube. detailsList contains all the
    // details, including those from the database, removed or not.
    val detailsFetched: (List<Detail>) -> Unit = { allDetailsUnordered ->
        run {
            // Add the details from the database
            val allDetailsIncludingOld = mutableListOf<Detail>()
            allDetailsIncludingOld.addAll(
                    VideoList.getAllDetailsFromDb(this, mPlaylistTitle))

            // Make sure there are no duplicates in the list. It's a bandaid, but that's fine.
            val allDetailsUnorderedWithoutDuplicates = removeDuplicates(allDetailsIncludingOld)

            //TODO: we probably shouldn't be doing all this on the UI thread
            runOnUiThread {
                val orderedByDateIncludingRemoved = PlaylistManipulator.orderByDate(allDetailsUnorderedWithoutDuplicates)

                mDetailsByDateIncludingRemoved = orderedByDateIncludingRemoved
                mDetailsByDate = RemovePrevious.filterOutRemoved(this, mPlaylistTitle, mDetailsByDateIncludingRemoved)

                // Now that we have the details, we can set visibility of warnings
                setNoVideosWarningsVisibility()

                // Now that we've got a list of details, we can prepare the Episode Pager and RecyclerView
                mEpisodeViewPagerAdapter = EpisodePagerAdapter(this, mDetailsByDate, {
                    mEpisodePager.setCurrentItem(mEpisodePager.currentItem - 1, true)
                }, {
                    mEpisodePager.setCurrentItem(mEpisodePager.currentItem + 1, true)
                })
                mEpisodePager.setAdapter(mEpisodeViewPagerAdapter)
                refreshEpisodePagerVisibility()

                mAdapter = RecyclerAdapter(this, mDetailsByDate, isSelected, onItemClick, removeBeforeDate)
                mAdapterInitialized = true
                mPlaylistRecyclerView.setAdapter(mAdapter)
                mAdapter.notifyItemRangeChanged(0, mDetailsByDate.size - 1)
                fetchVideosProgressSection.visibility = View.GONE

                // If the channel has no videos, don't play anything.
                if (mDetailsByDate.isNotEmpty()) {

                    // Get the default first video (the channel's first video)
                    val firstVideoId = mDetailsByDate[0].videoId

                    // Get the last video we were playing (which will be the next video in the playlist
                    // if it was queued at the end of the last watch session if it had time to try to load)
                    var videoIdToPlay = LastPlayedVideo.getLastPlayedVideoId(this, mPlaylistTitle)
                    if (videoIdToPlay == "") {
                        videoIdToPlay = firstVideoId
                    }

                    //val videoIdToPlay = sharedPref.getString(getString(R.string.currentVideoId), firstDetail.videoId).toString()

                    val channels = PlaylistChannels.getChannels(this, mPlaylistTitle)

                    var detailToPlay = VideoList.getDetail(this, channels, videoIdToPlay)
                    if (detailToPlay == null) {
                        // If we couldn't find a video to play, play the chronologically first video of the channel
                        val details = VideoList.getDetails(this, channels)
                        PlaylistManipulator.orderByDate(details)
                        detailToPlay = details[0]
                    }

                    playVideo(detailToPlay, true)

                    scrollToCurrentlyPlayingVideo()
                }
            }
        }
    }

    // Set up what happens when a playlist item is clicked
    val onItemClick: (Detail) -> Unit = { detail ->
        run {
            if (detail != mCurrentlyPlayingVideoDetail) {
                playVideo(detail, false)
            }
            // Hide the keyboard and collapse the slidingPanel if we click an item
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(mSearchEditText.getWindowToken(), 0)
            slidingLayout.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
        }
    }

    fun removeDuplicates(details: List<Detail>): List<Detail> {
        val withoutDuplicates = mutableListOf<Detail>()
        for (detail in details) {
            if (!withoutDuplicates.contains(detail)) {
                withoutDuplicates.add(detail)
            }
        }
        return withoutDuplicates
    }

    val isSelected: (Detail) -> Boolean = { detail ->
        run {
            detail == mCurrentlyPlayingVideoDetail
        }
    }

    val removeBeforeDate: (date: DateTime) -> Unit = { date ->
        run {
            removeVideosBeforeDate(date)
            notifyPlaylistItemsRemoved(date)
        }
    }

    private fun notifyPlaylistItemsRemoved(date: DateTime) {
        for (i in mAdapter.details.indices) {
            val iDetail = mAdapter.details[i]
            if (date == iDetail.dateUploaded) {
                // Make the RecyclerView items scroll up to fill in the space
                // TODO: Shouldn't this notify about more than just one detail?
                mPlaylistRecyclerView.adapter.notifyItemRemoved(i)
            }
        }
    }


    private fun setUpPlayer() {
        playerView.initialize(DeveloperKey.DEVELOPER_KEY, this)
    }

    private fun setUpEpisodePager() {
        mEpisodePager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == 0) { // finished scrolling
                    val detail = mEpisodeViewPagerAdapter.details[mEpisodePager.currentItem]
                    playVideo(detail)
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
            override fun onPageSelected(position: Int) {}
        })
    }

    private fun setUpPlaylist() {
        mPlaylistRecyclerView.setLayoutManager(mLinearLayoutManager)

        // Respond to keyboard up/down events
        val activityRootView = findViewById<LinearLayout>(R.id.layout)
        activityRootView.getViewTreeObserver().addOnGlobalLayoutListener({
            val heightDiff = activityRootView.getRootView().getHeight() - activityRootView.getHeight()

            if (heightDiff > 100) {
                // keyboard is up. Make the top half of screen go away to make room for the RecyclerView
                findViewById<LinearLayout>(R.id.playerContainer).visibility = View.GONE
                otherViews.visibility = View.GONE
                openPlaylist()
            } else {
                // keyboard is down. Bring the top half of the screen back.
                findViewById<LinearLayout>(R.id.playerContainer).visibility = View.VISIBLE
                otherViews.visibility = View.VISIBLE
            }
        })

        mUpButton.setOnClickListener {
            openPlaylist()
            scrollToTop()
        }
        mDownButton.setOnClickListener {
            openPlaylist()
            scrollToBottom()
        }
        mTargetButton.setOnClickListener {
            openPlaylist()
            scrollToCurrentlyPlayingVideo()
        }

        bar.getViewTreeObserver().addOnGlobalLayoutListener({
            if (bar.height > 0) {
                slidingLayout.panelHeight = bar.height
            }
        })

        // Flip the Expand/retract button depending on the sliding layout state
        slidingLayout.addPanelSlideListener(object : SlidingUpPanelLayout.PanelSlideListener {
            override fun onPanelStateChanged(panel: View?, previousState: SlidingUpPanelLayout.PanelState?, newState: SlidingUpPanelLayout.PanelState?) {
                if (newState == SlidingUpPanelLayout.PanelState.COLLAPSED) {
                    mExpandButton.scaleY = 1f
                } else {
                    mExpandButton.scaleY = -1f
                }
            }

            override fun onPanelSlide(panel: View, slideOffset: Float) {}
        })

        mExpandButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(p0: View?) {
                if (slidingLayout.panelState == SlidingUpPanelLayout.PanelState.COLLAPSED) {
                    openPlaylist()
                } else {
                    closePlaylist()
                }
            }
        })

        // Make sure the playlist starts out collapsed (e.g. when we receive a new intent from
        // the select channel screen)
        slidingLayout.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
    }

    private fun setUpSearch() {
        mSearchEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) {
                filter(text.toString())
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }
        })
    }

    private fun setUpPreferences() {
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        mPreferencesButton.setOnClickListener {

            showPreferencesActivity()
        }
        mBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, theIntent: Intent?) {

                when (theIntent?.action) {
                    PreferencesActivity.MANAGE_CHANNELS -> showManageChannelsActivity()
                    PreferencesActivity.WATCH_HISTORY -> showWatchHistoryActivity()
                    PreferencesActivity.CHANGE_PLAYLIST_TITLE -> showPlaylistChooserActivity()
                    PreferencesActivity.SHOW_ALL -> showAllVideos()
                }
            }
        }
    }

    fun showPreferencesActivity() {
        val i = Intent(this, PreferencesActivity::class.java)
        i.putExtra(PreferencesActivity.EXTRA_PLAYLIST_TITLE, mPlaylistTitle)

//        // Send in the playlist name so we know what text to display for the Change Playlist Name description
//        var playlistName = PlaylistChannels.getChannels(this, mChannel)
//        if (playlistName.isBlank()) {
//            playlistName = mChannel.name
//        }
//
//        i.putExtra("playlistName", playlistName)

        startActivity(i)
    }

    val respondToIncrementalVideosFetched: (List<PlaylistDetail>) -> Unit = { incrementalDetailsFetched ->
        run {

            runOnUiThread({

                gatheringTextView.text = getString(R.string.fetching)

                if (fetchVideosProgresBar.max == 0) {
                    // Prevent showing 0/0
                    fetchVideosProgressText.visibility = View.GONE
                } else {
                    fetchVideosProgressText.visibility = View.VISIBLE
                    mNumVideosFetched += incrementalDetailsFetched.size
                    // Sometimes the incrementalDetailsFetched number is a little bigger than mNumVideosFetched so to look right, we cap it
                    mNumVideosFetched = Math.min(mNumVideosFetched, fetchVideosProgresBar.max)
                    fetchVideosProgresBar.setProgress(mNumVideosFetched)
                    fetchVideosProgressText.setText(mNumVideosFetched.toString() + "/" + fetchVideosProgresBar.max)
                }
            })
        }
    }

    //region [initialization]
    override fun onInitializationSuccess(provider: YouTubePlayer.Provider, player: YouTubePlayer,
                                         wasRestored: Boolean) {
        this.player = player
        mPlayerInitialized = true
        player.setPlayerStateChangeListener(playerStateChangeListener)
        player.setPlaybackEventListener(playbackEventListener)

        // Specify that we want to handle fullscreen behavior ourselves.
        player.addFullscreenControlFlag(YouTubePlayer.FULLSCREEN_FLAG_CUSTOM_LAYOUT)
        player.setOnFullscreenListener(this)

        var controlFlags = player.fullscreenControlFlags
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        controlFlags = controlFlags or YouTubePlayer.FULLSCREEN_FLAG_ALWAYS_FULLSCREEN_IN_LANDSCAPE
        player.fullscreenControlFlags = controlFlags

        // We may get here after we failed to play the video if we tried playing it before
        // initializing the player. Now that it's initialized we can play the video.
        if (mCurrentlyPlayingVideoDetail != null) {
            playVideo(mCurrentlyPlayingVideoDetail)
        }
    }
    //endregion

    //region [lifecycle]
    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(mBroadcastReceiver)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }
    //endregion

    //region [layout]
    // If we press back when the sliding panel is visible, minimize it
    override fun onBackPressed() {
        if (slidingLayout.panelState != SlidingUpPanelLayout.PanelState.COLLAPSED) {
            slidingLayout.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
        } else {
            super.onBackPressed()
        }
    }
    //endregion

    //region [playlist functions]
    private fun refreshPlaylist() {
        updateAdapters(mDetailsByDate)
        scrollToCurrentlyPlayingVideo()
    }

    fun openPlaylist() {
        slidingLayout.panelState = SlidingUpPanelLayout.PanelState.EXPANDED
    }

    fun closePlaylist() {
        slidingLayout.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
    }
    //endregion

    //region [handle removed videos]
    fun removeVideosBeforeDate(date: DateTime) {

        RemovePrevious.setRemovedBeforeDate(this, mPlaylistTitle, date)

        // Update our cached lists
        mDetailsByDate = RemovePrevious.filterOutRemoved(this, mPlaylistTitle, mDetailsByDate)
        updateAdapters(mDetailsByDate)

        // Removing videos may have caused the No Videos warning to appear, so make that happen
        setNoVideosWarningsVisibility()
    }

    fun updateAdapters(details: List<Detail>) {
        if (mAdapterInitialized) {
            mAdapter.details = details
            mAdapter.notifyDataSetChanged()
            mEpisodeViewPagerAdapter.details = details
            mEpisodeViewPagerAdapter.notifyDataSetChanged()
        }

        refreshEpisodePagerVisibility()
    }

    fun refreshEpisodePagerVisibility() {
        if (mDetailsByDate.isEmpty()) {
            mEpisodePager.visibility = View.GONE
        } else {
            mEpisodePager.visibility = View.VISIBLE
        }
    }


    fun showWatchHistoryActivity() {
        val watchHistoryIntent = Intent(this, WatchHistoryActivity::class.java)
        // TODO: watchHistoryIntent should contain a playlistTitle, but currently it's expecting a channel
        watchHistoryIntent.putExtra("playlistTitle", mPlaylistTitle)
        watchHistoryIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivityForResult(watchHistoryIntent, WATCH_HISTORY_REQUEST)
    }

    // TODO: Make this work by clearing playlist title and removing all videos, etc.
    fun showPlaylistChooserActivity() {
        val i: Intent = Intent(this, PlaylistChooserActivity::class.java)
        i.putExtra(getString(R.string.launchedFromSettings), true)
        startActivity(i)
    }

    fun showManageChannelsActivity() {
        val manageChannelsActivityIntent = Intent(this, ManageChannelsActivity::class.java)
        manageChannelsActivityIntent.putExtra(getString(R.string.playlistTitle), mPlaylistTitle)
        manageChannelsActivityIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivityForResult(manageChannelsActivityIntent, MANAGE_CHANNELS_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Check which request we're responding to
        if (requestCode == WATCH_HISTORY_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                // We clicked a video.  Find it and play it.
                val videoId = data?.getStringExtra(WatchHistoryActivity.WATCH_HISTORY_DETAIL)
                val detailToPlay = mDetailsByDateIncludingRemoved.find { it.videoId == videoId }
                playVideo(detailToPlay)

                // Collapse the playlist because the user had to open it to get to the preferences
                slidingLayout.panelState = SlidingUpPanelLayout.PanelState.COLLAPSED
            } else if (resultCode == Activity.RESULT_CANCELED) {
                // Launch the preferences pane so it looks like we went back to it from the warch history
                showPreferencesActivity()
            }
        } else if (requestCode == CHANNEL_SELECT_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                // The user selected an initial channel to add to our list
                val channel = data?.getSerializableExtra("channel") as Channel
                PlaylistChannels.addChannel(this, mPlaylistTitle, channel)
                // channel is the only one, so we can send it in and it'll be the only one refreshed
                loadPlaylist(listOf(channel.name))
            }
        } else if (requestCode == MANAGE_CHANNELS_REQUEST) {
            // The channels have been added/removed from the playlist in PlaylistChannels, so just
            // refresh the playlist and it'll have the new/removed channels.
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    val newChannels = data.getStringArrayListExtra("newChannelNames")
                    loadPlaylist(newChannels)
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                // Load all channels
                val channels = PlaylistChannels.getChannels(this, mPlaylistTitle)
                val channelStrings = channels.map { it -> it.name }
                loadPlaylist(channelStrings)
            }

            // Adding/removing channels could cause the warning texts to show up/go away
            setNoVideosWarningsVisibility()
        }
    }

    fun showAllVideos() {
        RemovePrevious.unRemove(this, mPlaylistTitle)

        // Update our cached list
        mDetailsByDate = mDetailsByDateIncludingRemoved

        if (mAdapterInitialized)
            refreshPlaylist()

        // unRemoving all videos may have caused the No Videos warning to disappear, so make that happen
        setNoVideosWarningsVisibility()

        Toast.makeText(this, getString(R.string.allVideosShown),
                Toast.LENGTH_SHORT).show()
    }
    //endregion

    // Filter the list of details by the search term in the title
    private fun filter(query: String) {
        var lowerCaseQuery = query.toLowerCase()

        // Ignore a space at the end
        if(lowerCaseQuery.endsWith(" ")) {
            lowerCaseQuery = lowerCaseQuery.substring(0, lowerCaseQuery.length - 1)
        }

        val filteredNames = mDetailsByDate.filter {
            it.title.toLowerCase().contains(lowerCaseQuery) }

        mAdapter.details = filteredNames.toList()
        mAdapter.notifyDataSetChanged()
    }


    private class MyPlayerStateChangeListener(val videoEndCallback: () -> Unit) : YouTubePlayer.PlayerStateChangeListener {
        override fun onVideoEnded() {
            // Play the next video by calling the callback
            videoEndCallback()
        }
        override fun onAdStarted() {}
        override fun onLoading() {}
        override fun onVideoStarted() {}
        override fun onLoaded(p0: String?) {}
        override fun onError(p0: YouTubePlayer.ErrorReason?) {}
    }

    private class MyPlaybackEventListener(
            val recordCurrentTimeCallback: () -> Unit,
            val recordCurrentTimeHandler: Handler) : YouTubePlayer.PlaybackEventListener {

        override fun onPlaying() {
            // This runnable happens every 5 seconds and records the current play time to
            // SharedPreferences, until recordCurrentTimeHandler.removeCallbacksAndMessages(null)
            // is called
            val backupCurrentTime = object: Runnable {
                override fun run() {
                    recordCurrentTimeCallback()
                    recordCurrentTimeHandler.postDelayed(this, 5000)
                }
            }
            recordCurrentTimeHandler.post(backupCurrentTime)
        }

        override fun onStopped() {
            // Prevent the current time caching from happening every 5 seconds when we're paused
            recordCurrentTimeHandler.removeCallbacksAndMessages(null)
        }

        override fun onPaused() {
            recordCurrentTimeCallback()

            // Prevent the current time caching from happening every 5 seconds when we're paused
            recordCurrentTimeHandler.removeCallbacksAndMessages(null)
        }

        override fun onSeekTo(endPositionMillis: Int) {}
        override fun onBuffering(isBuffering: Boolean) {}
    }


    private val playNextVideo: () -> Unit = {
        // First reset the watched time to 0 for the current video
        // so we start over at the beginning when playing this video next
        val currentlyPlaying = mCurrentlyPlayingVideoDetail
        if (currentlyPlaying != null) {
            WatchedMillis.addOrUpdateWatchedMillis(this, currentlyPlaying, 0)
        }

        // Cue up the next video
        val nextVideoDetail: Detail? = getNextVideo()
        if (nextVideoDetail != null) {
            // Play the next video, but don't scroll to it in case the user is looking somewhere else in the playlist
            playVideo(nextVideoDetail, true)
        }
    }

    private val recordCurrentTime: () -> Unit = {
        // The video was paused (or minimized or otherwise caused to pause playback)
        // Record the time we paused at so we can restore it when the app reloads
        val d: Detail? = mCurrentlyPlayingVideoDetail
        if (d != null) {
            if (mPlayerInitialized) {
                WatchedMillis.addOrUpdateWatchedMillis(this, d, player.currentTimeMillis)
            }
        }
    }

    private fun getNextVideo() : Detail? {
        // If we're currently playing a video, start the search. Otherwise return null because we
        // must be at the end of the playlist.
        val currentlyPlayingVideoDetail = mCurrentlyPlayingVideoDetail
        if (currentlyPlayingVideoDetail != null) {

            // We need to know the list of removed videos so we make sure we don't play one that is
            // meant to be removed. But when finding our current place in the playlist, we need to work
            // with all videos including the removed ones in case the user just specified to remove a video
            // they're currently playing
            val removeBeforeDate = RemovePrevious.getRemoveBeforeDate(this, mPlaylistTitle)

            // This will be true once we found the current video. Once we have that, we keep looping
            // through all the videos until we find the one after removeBeforeDate
            var foundCurrentlyPlayingVideo = false

            // This will be true once we've found a video after removeBeforeDate, meaning we can play the next
            // video (if we've found the currently playing video)
            var afterRemoveBeforeDate = false

            // As explained above, we need to search through all the videos,
            // in order to find the currently playing video.
            for (detail in mDetailsByDateIncludingRemoved) {
                if (foundCurrentlyPlayingVideo && afterRemoveBeforeDate) {
                    return detail
                }

                if (detail == mCurrentlyPlayingVideoDetail)
                    foundCurrentlyPlayingVideo = true

                if (removeBeforeDate != null) {
                    if (detail.dateUploaded.value > removeBeforeDate.value) {
                        afterRemoveBeforeDate = true
                    }
                } else {
                    afterRemoveBeforeDate = true
                }
            }
        }
        return null
    }



    // Scroll the recyclerView to the playing video
    fun scrollToCurrentlyPlayingVideo() {
        val index = mAdapter.details.indexOf(mCurrentlyPlayingVideoDetail)

        // Scroll to one behind the selected video to show the selected video second. And scroll
        // to 0, not negative one, if we're on the first video
        val scrollTo = Math.max(index - 1, 0)

        runOnUiThread {
            // Scroll with an offset so that the selected video is one item down in the list
            mLinearLayoutManager.scrollToPositionWithOffset(scrollTo, 0)
        }
    }

    fun scrollToTop() {
        val index = 0
        runOnUiThread {
            mLinearLayoutManager.scrollToPosition(index)
        }
    }
    fun scrollToBottom() {
        val index = if (mPlaylistRecyclerView.adapter == null) -1 else mPlaylistRecyclerView.adapter.itemCount - 1
        if (index > -1) {
            runOnUiThread {
                mLinearLayoutManager.scrollToPosition(index)
            }
        }
    }

    override val youTubePlayerProvider: YouTubePlayer.Provider
        get() = playerView

    override fun onFullscreen(isFullscreen: Boolean) {
        fullscreen = isFullscreen
    }

    fun playVideo(detail: Detail?, centerPlaylistItem: Boolean = true) {
        if (detail != null) {
            mCurrentlyPlayingVideoDetail = detail


            val startTimeMillis = WatchedMillis.getWatchedMillis(this, detail)

            runOnUiThread {
                // Find the right detail to switch the episode viewpager to
                mEpisodePager.currentItem = mEpisodeViewPagerAdapter.details.indexOf(detail)

                if (mPlayerInitialized)
                    player.loadVideo(detail.videoId, startTimeMillis)

                // Refresh the RecyclerAdapter to get the currently playing highlight right
                mPlaylistRecyclerView.adapter.notifyDataSetChanged()
            }

            // Save the Detail to the database so we can start there next time
            // this database uses ChannelId for the key and VideoId for the value
            LastPlayedVideo.addOrUpdateLastPlayedVideo(this, mPlaylistTitle, detail)


            if (centerPlaylistItem)
                scrollToCurrentlyPlayingVideo()

            // Save the detail to Watch History
            WatchHistory.addDetail(this, mPlaylistTitle, detail)
        } else {
            if (mPlayerInitialized) {
                // For some reason, the sliding panel is unresponsive unless we do this.
                player.release()
            }



        }
    }
}