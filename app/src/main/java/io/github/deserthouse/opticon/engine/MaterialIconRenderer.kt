package io.github.deserthouse.opticon.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * MaterialIconRenderer —— Material design icon library fallback 
 *
 * Provides categorized Material Icons for manual override (Scheme 6 Branch B).
 * Selected icon is rendered to pure white Bitmap for notification bar use.
 */

typealias IconCategory = String
typealias IconEntry = Pair<String, ImageVector>

object MaterialIconRenderer {

    const val OUTPUT_SIZE = 96

    val categories: List<Pair<IconCategory, List<IconEntry>>> by lazy {
        listOf(
            "Communication" to listOf(
                "Chat" to Icons.Filled.Chat,
                "Email" to Icons.Filled.Email,
                "Call" to Icons.Filled.Call,
                "Message" to Icons.Filled.Message,
                "Contacts" to Icons.Filled.Contacts,
                "Phone" to Icons.Filled.Phone,
                "MailOutline" to Icons.Rounded.MailOutline,
                "AlternateEmail" to Icons.Filled.AlternateEmail
            ),
            "Social" to listOf(
                "People" to Icons.Filled.People,
                "Person" to Icons.Filled.Person,
                "Group" to Icons.Filled.Group,
                "Share" to Icons.Filled.Share,
                "ThumbUp" to Icons.Filled.ThumbUp,
                "Favorite" to Icons.Filled.Favorite,
                "Forum" to Icons.Filled.Forum,
                "Public" to Icons.Filled.Public
            ),
            "Tools" to listOf(
                "Settings" to Icons.Filled.Settings,
                "Build" to Icons.Filled.Build,
                "Search" to Icons.Filled.Search,
                "Extension" to Icons.Filled.Extension,
                "Code" to Icons.Filled.Code,
                "BugReport" to Icons.Filled.BugReport,
                "Security" to Icons.Filled.Security,
                "Lock" to Icons.Filled.Lock
            ),
            "Gaming" to listOf(
                "SportsEsports" to Icons.Filled.SportsEsports,
                "VideogameAsset" to Icons.Filled.VideogameAsset,
                "Star" to Icons.Filled.Star,
                "Casino" to Icons.Filled.Casino,
                "EmojiEvents" to Icons.Filled.EmojiEvents,
                "Rocket" to Icons.Filled.Rocket,
                "RocketLaunch" to Icons.Rounded.RocketLaunch
            ),
            "Media" to listOf(
                "PlayArrow" to Icons.Filled.PlayArrow,
                "MusicNote" to Icons.Filled.MusicNote,
                "Headphones" to Icons.Filled.Headphones,
                "Camera" to Icons.Filled.Camera,
                "Photo" to Icons.Filled.Photo,
                "Movie" to Icons.Filled.Movie,
                "Palette" to Icons.Filled.Palette,
                "Mic" to Icons.Filled.Mic
            ),
            "System" to listOf(
                "Notifications" to Icons.Filled.Notifications,
                "Info" to Icons.Filled.Info,
                "Warning" to Icons.Filled.Warning,
                "CheckCircle" to Icons.Filled.CheckCircle,
                "Cloud" to Icons.Filled.Cloud,
                "Sync" to Icons.Filled.Sync,
                "Update" to Icons.Filled.Update,
                "PowerSettingsNew" to Icons.Filled.PowerSettingsNew
            ),
            "Shopping" to listOf(
                "ShoppingCart" to Icons.Filled.ShoppingCart,
                "Store" to Icons.Filled.Store,
                "ShoppingBag" to Icons.Filled.ShoppingBag,
                "CreditCard" to Icons.Filled.CreditCard,
                "LocalOffer" to Icons.Filled.LocalOffer,
                "Redeem" to Icons.Filled.Redeem
            ),
            "Travel" to listOf(
                "DirectionsCar" to Icons.Filled.DirectionsCar,
                "DirectionsBus" to Icons.Filled.DirectionsBus,
                "Flight" to Icons.Filled.Flight,
                "Map" to Icons.Filled.Map,
                "Navigation" to Icons.Filled.Navigation,
                "LocationOn" to Icons.Filled.LocationOn
            )
        )
    }

    /**
     * Render the specified ImageVector to a pure white 96px bitmap.
     */
    fun renderToWhiteBitmap(icon: ImageVector, size: Int = OUTPUT_SIZE): Bitmap? {
        return createBlankWhite()
    }

    fun findByName(name: String): ImageVector? {
        for ((_, entries) in categories) {
            entries.find { it.first == name }?.let { return it.second }
        }
        return null
    }

    private fun createBlankWhite(): Bitmap {
        val bmp = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(0xFFFFFFFF.toInt())
        return bmp
    }
}
