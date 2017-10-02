package com.mymikemiller.chronoplayer.yt

import android.accounts.Account
import android.content.Context
import android.os.AsyncTask
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.services.youtube.YouTube
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.model.Playlist
import com.google.api.services.youtube.model.PlaylistListResponse
import com.mymikemiller.chronoplayer.Channel
import com.mymikemiller.chronoplayer.Detail
import com.mymikemiller.chronoplayer.DeveloperKey
import java.io.IOException
import java.util.*

val HTTP_TRANSPORT = NetHttpTransport()
val JSON_FACTORY: JsonFactory = JacksonFactory()

/**
 *  A helper class that communicates with YouTube to make requests and return data. To use
 *  authenticated requests, an instance of YouTubeAPI must be constructed. Otherwise, the static
 *  methods may be used.
 */
class YouTubeAPI(context: Context, account: Account) {
    private val mYouTube: YouTube

    init {
        val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
                context,
                Collections.singleton(YOUTUBE_SCOPE));
        credential.setSelectedAccount(account);

        // Global instance of the HTTP transport
        val HTTP_TRANSPORT: HttpTransport = AndroidHttp.newCompatibleTransport();

        // Global instance of the JSON factory
        val JSON_FACTORY: JsonFactory = JacksonFactory.getDefaultInstance();

        mYouTube = YouTube.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName("ChronoPlayer")
                .build();
    }

    fun getPlaylist(title: String, callback: (Playlist?) -> Unit) {
        if (mYouTube == null) {
            // We're not authorized, so call the callback specifying null
            callback(null)
        } else {
            GetPlaylistTask(mYouTube, title, YOUTUBE_SCOPE, { playlist ->
                run {
                    callback(playlist)
                }
            }).execute()
        }
    }

    /**
     * AsyncTask that uses the specified AuthYoutube playlist API.
     */
    private class GetPlaylistTask(val authenticatedYoutube: YouTube, val title: String, val YOUTUBE_SCOPE: String, val callback: (Playlist?) -> Unit) : AsyncTask<Account, Unit, Playlist?>() {

        protected override fun onPreExecute(): Unit {
            //showProgressDialog();
        }

        override fun doInBackground(vararg params: Account?): Playlist? {
            try {
                val playlistsListByChannelIdResponse: PlaylistListResponse = authenticatedYoutube
                        .playlists()
                        .list("snippet")
                        .setMine(true)
                        .execute();

                val playlists = playlistsListByChannelIdResponse.getItems()

                // Get names of all connections
                for (playlist in playlists) {
                    // Got the subscriptions
                    println()
                    if (playlist.snippet.title == title) {
                        callback(playlist)
                    }
                }

                // we didn't find a playlist with the give title. Run the callback specifying null.
                callback(null)

            } catch (userRecoverableException: UserRecoverableAuthIOException) {
//                Log.w(TAG, "getSubscription:recoverable exception", userRecoverableException);
//                startActivityForResult(userRecoverableException.getIntent(), RC_RECOVERABLE);
            } catch (e: IOException) {
//                Log.w(TAG, "getSubscription:exception", e);
            }

            return null;
        }

        override fun onPostExecute(playlist: Playlist?): Unit {
//            hideProgressDialog();

            if (playlist != null) {
//                Log.d(TAG, "subscriptions : size=" + subscriptions.size());

            } else {
                // failed
                println()
            }
        }
    }

    // Static functions that don't require authoriation
    companion object {
        // Scope for modifying the user's private data
        val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube";

        /**
         * Define a global instance of a YouTube object, which will be used to make
         * YouTube Data API requests.
         */
        private val youtube: YouTube = YouTube.Builder(HTTP_TRANSPORT, JSON_FACTORY,
                HttpRequestInitializer { }).setApplicationName("chronoplayer").build()

        fun fetchAllDetails(channel: Channel,
                              stopAtDetail: Detail?,
                              setPercentageCallback: (totalVideos: kotlin.Int, currentVideoNumber: kotlin.Int) -> Unit,
                              callback: (details: List<Detail>) -> Unit) {

            // Clear the details in preparation of fetching them all
            allDetails = mutableListOf<Detail>()

            FetchNextDetailTask(channel,
                    "",
                    stopAtDetail,
                    accumulate,
                    setPercentageCallback,
                    callback).execute()
        }

        var allDetails = mutableListOf<Detail>()
        var prevNextPageToken = ""

        val accumulate: (channel: Channel,
                         detailList: List<Detail>,
                         nextPageToken: String,
                         stopAtDetail: Detail?,
                         setPercentageCallback: (totalVideos: kotlin.Int, currentVideoNumber: kotlin.Int) -> Unit,
                         callbackWhenDone: (detailList: List<Detail>) -> Unit
        ) -> Unit = { channel,
                      detailsList,
                      nextPageToken,
                      stopAtDetail,
                      setPercentageCallback,
                      callbackWhenDone ->
            run {
                prevNextPageToken = nextPageToken
                allDetails.addAll(detailsList)
                FetchNextDetailTask(channel,
                        nextPageToken,
                        stopAtDetail,
                        accumulate,
                        setPercentageCallback,
                        callbackWhenDone).execute()
            }
        }

        private class FetchNextDetailTask(val channel: Channel,
                                        var pageToken: String,
                                        val stopAtDetail: Detail?,
                                        val callback: (channel: Channel,
                                                 detailList: List<Detail>,
                                                 nextPageToken: String,
                                                 stopAtDetail: Detail?,
                                                 setPercentageCallback: (kotlin.Int, kotlin.Int) -> Unit,
                                                 callbackWhenDone: (detailList: List<Detail>) -> Unit) -> Unit,
                                        val setPercentageCallback: (totalVideos: kotlin.Int,
                                                      currentVideoNumber: kotlin.Int) -> Unit,
                                        val callbackWhenDone: (detailList: List<Detail>) -> Unit
                                              ) : AsyncTask<Unit, Unit, Unit>() {
            override fun doInBackground(vararg params: Unit?) {
                val videosListByUploadPlaylistIdRequest = youtube.PlaylistItems().list("snippet")
                videosListByUploadPlaylistIdRequest.playlistId = channel.uploadPlaylistId
                videosListByUploadPlaylistIdRequest.key = (DeveloperKey.DEVELOPER_KEY)
                videosListByUploadPlaylistIdRequest.maxResults = 50
                videosListByUploadPlaylistIdRequest.pageToken = pageToken

                val searchResponse = videosListByUploadPlaylistIdRequest.execute()
                var done = false
                val searchResultList = searchResponse.items
                if (searchResultList != null) {
                    val results: MutableList<Detail> = mutableListOf()
                    for (result in searchResultList) {
                        val thumbnail = if (result.snippet.thumbnails.standard != null) result.snippet.thumbnails.standard.url else result.snippet.thumbnails.high.url

                        val d = Detail(channel,
                                result.snippet.resourceId.videoId,
                                result.snippet.title,
                                result.snippet.description,
                                thumbnail,
                                result.snippet.publishedAt)

                        if (d == stopAtDetail) {
                            // Don't break here because our results come in out of order, so we need
                            // to keep looping to make sure we get all the new stuff
                            done = true
                        }

                        results.add(d)
                    }

                    // Let the caller know how close we are to being done
                    val overallTotalResults = searchResponse.pageInfo.totalResults
                    // We have to add searchResultList.size instead of just allDetails.size because allDetails won't be added to until the callback is called below
                    setPercentageCallback(Integer.valueOf(overallTotalResults), Integer.valueOf(allDetails.size + searchResultList.size))

                    if (done || searchResponse.nextPageToken == null) {
                        // This is the last page of results.
                        // Send the previous pageToken so we can cache the last pageToken to start on that page next time.
                        // Add them to allResults and call the final callback.
                        allDetails.addAll(results)
                        callbackWhenDone(allDetails)
                        return
                    }

                    callback(channel, results, searchResponse.nextPageToken, stopAtDetail, setPercentageCallback, callbackWhenDone)
                }
            }
        }

        fun fetchChannels(query: String, callback: (channels: List<Channel>) -> Unit) {
            FetchChannelsTask(query, callback).execute()
        }

        private class FetchChannelsTask(val query: String, val callback: (channels: List<Channel>) -> Unit) : AsyncTask<Unit, Unit, Unit>() {
            override fun doInBackground(vararg params: Unit?) {
                val parameters = hashMapOf<String, String>()
                parameters.put("part", "snippet")
                parameters.put("maxResults", "25")
                parameters.put("q", query)
                parameters.put("type", "channel")

                val searchListByKeywordRequest = youtube.search().list(parameters.get("part").toString())
                searchListByKeywordRequest.key = DeveloperKey.DEVELOPER_KEY

                if (parameters.containsKey("maxResults")) {
                    searchListByKeywordRequest.setMaxResults((parameters.get("maxResults").toString()).toLong())
                }
                if (parameters.containsKey("q") && parameters.get("q") != "") {
                    searchListByKeywordRequest.setQ(parameters.get("q").toString())
                }

                if (parameters.containsKey("type") && parameters.get("type") != "") {
                    searchListByKeywordRequest.setType(parameters.get("type").toString())
                }
                val response = searchListByKeywordRequest.execute()

                val channels = mutableListOf<Channel>()
                for (item in response.items) {
                    fetchUploadPlaylistId(item.snippet.channelId, { uploadPlaylistId ->
                        run {

                            val channel = Channel(
                                    item.snippet.channelId,
                                    item.snippet.channelTitle,
                                    uploadPlaylistId,
                                    item.snippet.thumbnails.default.url)
                            channels.add(channel)

                            callback(channels)
                        }
                    })
                }
            }
        }


        fun fetchUploadPlaylistId(channelId: String, callback: (channels: String) -> Unit) {
            FetchUploadPlaylistIdTask(channelId, callback).execute()
        }

        private class FetchUploadPlaylistIdTask(val channelId: String, val callback: (uploadId: String) -> Unit) : AsyncTask<Unit, Unit, Unit>() {
            override fun doInBackground(vararg params: Unit?) {
                val parameters = hashMapOf<String, String>()
                parameters.put("part", "contentDetails")
                parameters.put("id", channelId)

                val uploadIdRequest = youtube.channels().list(parameters.get("part").toString())
                uploadIdRequest.key = DeveloperKey.DEVELOPER_KEY

                if (parameters.containsKey("part")) {
                    uploadIdRequest.setPart((parameters.get("part").toString()))
                }
                if (parameters.containsKey("id") && parameters.get("id") != "") {
                    uploadIdRequest.setId(parameters.get("id").toString())
                }

                val response = uploadIdRequest.execute()
                val uploadPlaylistId = response.items[0].contentDetails.relatedPlaylists.uploads

                callback(uploadPlaylistId)
            }
        }
    }
}