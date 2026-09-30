package com.stratum.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.designsystem.theme.StratumTheme

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      StratumTheme(palette = IgboContentPack.palette) {
        StratumApp(modifier = Modifier.fillMaxSize())
      }
    }
  }
}
