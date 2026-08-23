package com.libremobileos.sidebar.bean

import android.graphics.drawable.Drawable

data class SidebarAppInfo(
    val label: String,
    val icon: Drawable,
    val packageName: String,
    val activityName: String,
    val userId: Int,
    var isPinned: Boolean = false,
    var isPredicted: Boolean = false
)
