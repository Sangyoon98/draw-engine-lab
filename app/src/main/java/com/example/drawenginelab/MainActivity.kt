package com.example.drawenginelab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drawenginelab.canvas.CanvasScreen
import com.example.drawenginelab.ink.InkScreen
import com.example.drawenginelab.nativegl.NativeGlScreen
import com.example.drawenginelab.ui.theme.DrawEngineLabTheme

private enum class Screen { HOME, INK, CANVAS, NATIVEGL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DrawEngineLabTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                onInk = { screen = Screen.INK },
                onCanvas = { screen = Screen.CANVAS },
                onNativeGl = { screen = Screen.NATIVEGL },
            )
            Screen.INK -> InkScreen(onBack = { screen = Screen.HOME })
            Screen.CANVAS -> CanvasScreen(onBack = { screen = Screen.HOME })
            Screen.NATIVEGL -> NativeGlScreen(onBack = { screen = Screen.HOME })
        }
    }
}

@Composable
private fun HomeScreen(onInk: () -> Unit, onCanvas: () -> Unit, onNativeGl: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("드로잉 구현 3종 비교", fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "삼성노트는 자체 C++(NDK) 엔진으로 구현. 그 접근을 세 방식으로 비교하는 테스트 앱.",
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(24.dp))

        Button(onClick = onInk, modifier = Modifier.fillMaxWidth()) { Text("Ink API 버전") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Google 공식 Ink 엔진을 그대로 사용. 필기감 최상·저지연 내장, 대신 구형 HW 약함·아직 베타.",
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(16.dp))

        Button(onClick = onCanvas, modifier = Modifier.fillMaxWidth()) { Text("Canvas 버전 (스마트러닝+삼성노트 하이브리드)") }
        Spacer(Modifier.height(4.dp))
        Text(
            "스마트러닝 렌더 방식(Canvas+비트맵 베이킹)에 삼성노트식 펜 기능을 얹음. 호환성 최강·획 많아도 빠름, 대신 확대 시 품질 저하(래스터)·필기감 약간 아쉬움.",
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(16.dp))

        Button(onClick = onNativeGl, modifier = Modifier.fillMaxWidth()) { Text("NDK GL 버전 (삼성 구조 재현)") }
        Spacer(Modifier.height(4.dp))
        Text(
            "역공학으로 본 삼성 구조(C++ + GLES)를 직접 추측 설계. GPU 벡터라 획 많아도 빠르고 확대해도 선명, 품질·호환·성능 균형. 대신 C++ 유지비.",
            fontSize = 11.sp,
        )
    }
}
