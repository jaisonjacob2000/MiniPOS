package com.jaison.minipos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jaison.minipos.ui.MiniPosTheme
import com.jaison.minipos.ui.PosScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiniPosTheme {
                PosScreen()
            }
        }
    }
}
