package com.tapcast.app

/** A show: identified by its RSS feed URL (the podcast ecosystem's primary key). */
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artUrl: String?,
    val description: String = ""
)

/** One episode of a show. [guid] is unique within the feed; enclosure is the audio. */
data class Episode(
    val guid: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val pubDateMs: Long,
    val durationSec: Int,
    val description: String,
    val sizeBytes: Long
) {
    /** Stable per-episode key for prefs + download file names. */
    val key: String get() = (feedUrl + "|" + guid).hashCode().toUInt().toString(16)
}
