package dev.androidtransfer.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import dev.androidtransfer.app.R
import dev.androidtransfer.app.core.transfer.TransferCategory

/** One shared icon+label mapping per category, used by both the category picker and the progress checklist. */
data class CategoryUiInfo(val category: TransferCategory, val labelRes: Int, val icon: ImageVector)

val categoryUiInfoList = listOf(
    CategoryUiInfo(TransferCategory.CONTACTS, R.string.category_contacts, Icons.Filled.Contacts),
    CategoryUiInfo(TransferCategory.CALL_LOG, R.string.category_call_log, Icons.Filled.Call),
    CategoryUiInfo(TransferCategory.CALENDAR, R.string.category_calendar, Icons.Filled.Event),
    CategoryUiInfo(TransferCategory.MEDIA, R.string.category_media, Icons.Filled.Image),
    CategoryUiInfo(TransferCategory.FILES, R.string.category_files, Icons.Filled.Folder),
    CategoryUiInfo(TransferCategory.INSTALLED_APPS, R.string.category_installed_apps, Icons.Filled.Apps),
    CategoryUiInfo(TransferCategory.WHATSAPP_MEDIA, R.string.category_whatsapp_media, Icons.Filled.Chat),
    CategoryUiInfo(TransferCategory.CUSTOM_FOLDER, R.string.category_custom_folder, Icons.Filled.FolderOpen),
    CategoryUiInfo(TransferCategory.WALLPAPER, R.string.category_wallpaper, Icons.Filled.Wallpaper),
)

fun categoryUiInfo(category: TransferCategory): CategoryUiInfo =
    categoryUiInfoList.first { it.category == category }
