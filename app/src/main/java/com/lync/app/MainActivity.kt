package com.lync.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.lync.app.ui.CampusRoot

// AppCompatActivity (not ComponentActivity) so the in-app EN/FR switch works on every Android version.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LyncApp).container
        setContent { CampusRoot(container) }
    }
}
