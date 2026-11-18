package com.example.ui.screens.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.PuckPreferences

data class ShortcutMeta(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    val color: Color
)

fun getShortcutMeta(key: String): ShortcutMeta {
    val standardBrown = Color(0xFFB08968)
    return when (key) {
        "circle_to_search" -> ShortcutMeta("Circle to Search", "Visual AI", R.drawable.ic_puck_circle_search, standardBrown)
        "flashlight" -> ShortcutMeta("Flashlight", "Torch", R.drawable.ic_puck_flashlight, standardBrown)
        "screenshot" -> ShortcutMeta("Screenshot", "Capture", R.drawable.ic_puck_screenshot, standardBrown)
        "dnd" -> ShortcutMeta("Do Not Disturb", "Silence", R.drawable.ic_puck_dnd, standardBrown)
        "audio" -> ShortcutMeta("Audio Profile", "Volume", R.drawable.ic_puck_audio, standardBrown)
        "brightness" -> ShortcutMeta("Brightness", "Display", R.drawable.ic_puck_brightness, standardBrown)
        "auto_rotate" -> ShortcutMeta("Auto-Rotate", "Rotation", R.drawable.ic_puck_autorotate, standardBrown)
        "smart_puck_app" -> ShortcutMeta("Settings", "App", R.drawable.ic_puck_settings, standardBrown)
        "lock_screen" -> ShortcutMeta("Lock Screen", "Security", R.drawable.ic_puck_lock, standardBrown)
        else -> ShortcutMeta(key, "Action", R.drawable.ic_puck_settings, standardBrown)
    }
}

@Composable
fun ShortcutArrangementCard(
    prefs: PuckPreferences,
    modifier: Modifier = Modifier
) {
    val menuOrder by prefs.menuOrderState.collectAsState()
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_menu_order"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with Reset action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shortcuts Order",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.basicMarquee()
                )

                TextButton(
                    onClick = {
                        prefs.resetMenuOrder()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    modifier = Modifier.testTag("btn_reset_menu_order")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Clean list of shortcuts
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                menuOrder.forEachIndexed { index, itemKey ->
                    val meta = getShortcutMeta(itemKey)
                    val isFirst = index == 0
                    val isLast = index == menuOrder.size - 1

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reorder_row_$itemKey")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Position Pill
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Unified Precision Vector Icon Badge
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(meta.color.copy(alpha = 0.14f), RoundedCornerShape(11.dp))
                                    .border(1.dp, meta.color.copy(alpha = 0.30f), RoundedCornerShape(11.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = meta.iconRes),
                                    contentDescription = meta.title,
                                    tint = meta.color,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Single line non-wrapping text with basicMarquee
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = meta.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.basicMarquee()
                                )
                                Text(
                                    text = meta.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.basicMarquee()
                                )
                            }

                            // Stepper Buttons
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        if (!isFirst) {
                                            prefs.moveMenuItem(index, index - 1)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    enabled = !isFirst,
                                    modifier = Modifier.size(32.dp).testTag("btn_move_up_$itemKey")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Up",
                                        tint = if (!isFirst) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (!isLast) {
                                            prefs.moveMenuItem(index, index + 1)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    enabled = !isLast,
                                    modifier = Modifier.size(32.dp).testTag("btn_move_down_$itemKey")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Down",
                                        tint = if (!isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
