package com.example.localdroid.data

data class VideoInfo(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val uploader: String?,
    val qualities: List<QualityOption>
)

data class QualityOption(
    val label: String,
    val formatCode: String
)
