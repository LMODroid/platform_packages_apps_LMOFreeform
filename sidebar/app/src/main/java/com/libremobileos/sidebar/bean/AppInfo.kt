package com.libremobileos.sidebar.bean

import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val icon: Drawable,
    val packageName: String,
    val activityName: String,
    val userId: Int,
    val isPinned: Boolean = true
)
