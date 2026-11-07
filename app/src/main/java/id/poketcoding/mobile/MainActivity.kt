package id.poketcoding.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import id.poketcoding.mobile.ui.AppRoot
import id.poketcoding.mobile.ui.rememberAppState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot(rememberAppState())
        }
    }
}
