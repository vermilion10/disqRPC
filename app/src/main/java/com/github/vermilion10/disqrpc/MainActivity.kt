package com.github.vermilion10.disqrpc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.vermilion10.disqrpc.ui.MainScreen
import com.github.vermilion10.disqrpc.ui.theme.DisqRPCTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DisqRPCTheme {
                MainScreen()
            }
        }
    }
}
