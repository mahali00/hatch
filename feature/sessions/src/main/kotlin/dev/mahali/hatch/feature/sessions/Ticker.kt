package dev.mahali.hatch.feature.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

@Composable
internal fun rememberNow(periodMillis: Long, key: Any? = null): Long {
    val now by produceState(System.currentTimeMillis(), periodMillis, key) {
        value = System.currentTimeMillis()
        while (true) {
            delay(periodMillis)
            value = System.currentTimeMillis()
        }
    }
    return now
}
