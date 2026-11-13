package com.footagevault.app.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Players for videos on the phone. ExoPlayer's default buffer is sized for streaming and can hold about 128 MB of a
 * high-bitrate phone video in the app's memory. With the viewer's neighbour pages and the grid's playing tiles
 * there can be several players at once, which ran the app out of memory (crash reports from 1.17.3). A local file
 * reads instantly, so a few seconds and at most 16 MB of buffer per player is plenty.
 */
object Players {
    @OptIn(UnstableApi::class)
    fun local(ctx: Context): ExoPlayer.Builder = ExoPlayer.Builder(ctx).setLoadControl(
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(/* min */ 2_000, /* max */ 5_000, /* to start */ 500, /* after a stall */ 1_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    )
}
