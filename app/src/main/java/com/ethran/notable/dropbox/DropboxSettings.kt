package com.ethran.notable.dropbox

import kotlinx.serialization.Serializable

const val DROPBOX_SETTINGS_KEY = "DROPBOX_SETTINGS"
const val DROPBOX_APP_KEY = "57o2pzksgtv8xhq"

@Serializable
data class DropboxSettings(
    val enabled: Boolean = false,
    val accessToken: String = "",
    val refreshToken: String = "",
    val accountName: String = "",
    val dropboxFolder: String = "/notable",
    val filelistPath: String = "",  // local path to filelist.txt
)
