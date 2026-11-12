package dev.mahali.hatch.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

public val HatchBackground: Color = Color(0xFF1B1E1D)

@Composable
public fun HatchIcon(modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.hatch_icon), contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(24)),
    )
}

