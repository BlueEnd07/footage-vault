package com.footagevault.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import com.footagevault.app.work.BackupWorker
import com.footagevault.app.work.Notify
import org.osmdroid.config.Configuration

class VaultApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
        Configuration.getInstance().userAgentValue = "FootageVault/1.0 (Android)"
        BackupWorker.schedule(this, replace = true)  // also picks up new retry rules after an update
    }

    /** Thumbnails: a frame from the phone's own file, or YouTube's thumbnail once it's gone from the phone. */
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(300L * 1024 * 1024).build() }
        .crossfade(150)
        .build()
}
