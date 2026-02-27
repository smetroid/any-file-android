// app/src/main/java/com/anyproto/anyfile/ui/MainActivity.kt
package com.anyproto.anyfile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.anyproto.anyfile.ui.navigation.AnyFileNavGraph
import com.anyproto.anyfile.ui.theme.AnyFileTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity for the AnyFile app
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnyFileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnyFileNavGraph()
                }
            }
        }
    }
}
