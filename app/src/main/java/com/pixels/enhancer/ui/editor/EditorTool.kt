package com.pixels.enhancer.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.pixels.enhancer.R

/** The editor's tool strip, in the order a typical edit flows: look, framing, global, local, history. */
enum class EditorTool(val labelRes: Int, val icon: ImageVector) {
    PRESETS(R.string.tool_presets, Icons.Outlined.Style),
    AUTO(R.string.tool_auto, Icons.Outlined.AutoFixHigh),
    CROP(R.string.tool_crop, Icons.Outlined.Crop),
    LIGHT(R.string.tool_light, Icons.Outlined.WbSunny),
    COLOR(R.string.tool_color, Icons.Outlined.Palette),
    EFFECTS(R.string.tool_effects, Icons.Outlined.BlurOn),
    DETAIL(R.string.tool_detail, Icons.Outlined.Texture),
    OPTICS(R.string.tool_optics, Icons.Outlined.Camera),
    GEOMETRY(R.string.tool_geometry, Icons.Outlined.Transform),
    MASKING(R.string.tool_masking, Icons.Outlined.Gradient),
    HEALING(R.string.tool_healing, Icons.Outlined.Healing),
    VERSIONS(R.string.tool_versions, Icons.Outlined.History),
}
