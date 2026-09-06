package com.ascan.ascanagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ascan.ascanagent.ui.HomeScreen
import com.ascan.ascanagent.ui.ScanViewModel
import com.ascan.ascanagent.ui.theme.AScanTheme
import com.ascan.ascanagent.ui.theme.Bg

class MainActivity : ComponentActivity() {

    private val vm: ScanViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ScanViewModel(application) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AScanTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    HomeScreen(vm)
                }
            }
        }
    }
}
