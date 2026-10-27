package com.ethic.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.AppEvent
import com.ethic.messenger.data.Repo
import com.ethic.messenger.ui.AuthScreen
import com.ethic.messenger.ui.Bg
import com.ethic.messenger.ui.CallPill
import com.ethic.messenger.ui.CallScreen
import com.ethic.messenger.ui.ChatScreen
import com.ethic.messenger.ui.EditProfileScreen
import com.ethic.messenger.ui.EthicTheme
import com.ethic.messenger.ui.GroupScreen
import com.ethic.messenger.ui.HomeScreen
import com.ethic.messenger.ui.ImageScreen
import com.ethic.messenger.ui.IncomingScreen
import com.ethic.messenger.ui.Nav
import com.ethic.messenger.ui.PickerScreen
import com.ethic.messenger.ui.Route
import com.ethic.messenger.ui.UserScreen

class MainActivity : ComponentActivity() {
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        setContent { EthicTheme { Root { ready = true } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        CallManager.handleIntent(intent)
        intent?.getLongExtra("chatId", 0L)?.takeIf { it > 0 }?.let { Nav.openChat(it) }
    }
}

private enum class Boot { LOADING, AUTH, APP }

@Composable
private fun Root(onReady: () -> Unit) {
    val ctx = LocalContext.current
    var boot by remember { mutableStateOf(Boot.LOADING) }
    val me by Repo.me.collectAsState()
    val loggedOut by Repo.loggedOut.collectAsState()
    val call by CallManager.state.collectAsState()
    val incoming by CallManager.incoming.collectAsState()

    LaunchedEffect(Unit) {
        Repo.loadConfig()
        boot = if (Repo.restore()) Boot.APP else Boot.AUTH
        onReady()
    }
    LaunchedEffect(loggedOut) { if (loggedOut) { Nav.stack.clear(); boot = Boot.AUTH } }
    LaunchedEffect(me) { if (me != null) boot = Boot.APP }
    LaunchedEffect(Unit) {
        Repo.events.collect { if (it is AppEvent.Toast) Toast.makeText(ctx, it.text, Toast.LENGTH_SHORT).show() }
    }

    // Mic / camera / notifications are asked the first time they are needed.
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    fun need(vararg p: String) = p.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
    fun ask(vararg p: String) { need(*p).takeIf { it.isNotEmpty() }?.let { perms.launch(it.toTypedArray()) } }
    LaunchedEffect(boot) {
        if (boot == Boot.APP && Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS)
    }
    val askCam by CallManager.askCamera.collectAsState()
    LaunchedEffect(askCam) { if (askCam > 0) ask(Manifest.permission.CAMERA) }
    LaunchedEffect(call?.chatId) {
        val c = call ?: return@LaunchedEffect
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (c.camOn) list += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        ask(*list.toTypedArray())
    }

    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Color.White) {
        when (boot) {
            Boot.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            Boot.AUTH -> AuthScreen()
            Boot.APP -> {
                Column(Modifier.fillMaxSize()) {
                    val c = call
                    val pill = c != null && c.minimized
                    if (pill) CallPill(c!!)
                    // The pill already covers the status bar: screens below must not pad for it again.
                    Box(Modifier.weight(1f).then(if (pill) Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else Modifier)) {
                        HomeScreen()
                        val top = Nav.stack.lastOrNull()
                        val depth = Nav.stack.size
                        var lastDepth by remember { mutableIntStateOf(0) }
                        val forward = depth >= lastDepth
                        LaunchedEffect(depth) { lastDepth = depth }
                        BackHandler(enabled = top != null) { Nav.pop() }
                        AnimatedContent(
                            top,
                            transitionSpec = {
                                if (forward) slideInHorizontally { it } togetherWith fadeOut()
                                else fadeIn() togetherWith slideOutHorizontally { it }
                            },
                            label = "nav",
                        ) { r ->
                            if (r != null) Box(Modifier.fillMaxSize().background(Bg)) { Screen(r) }
                        }
                    }
                }
                AnimatedVisibility(call != null && call?.minimized == false, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    call?.let { CallScreen(it) }
                }
                AnimatedVisibility(incoming != null, enter = fadeIn(), exit = fadeOut()) {
                    incoming?.let { ev ->
                        IncomingScreen(ev) { video ->
                            if (video) ask(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else ask(Manifest.permission.RECORD_AUDIO)
                            CallManager.accept(video)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(r: Route) {
    when (r) {
        is Route.ChatR -> ChatScreen(r.id)
        is Route.UserR -> UserScreen(r.id)
        is Route.GroupR -> GroupScreen(r.id)
        Route.EditProfile -> EditProfileScreen()
        is Route.Picker -> PickerScreen(r.mode, r.chatId)
        is Route.ImageR -> ImageScreen(r.url)
        Route.Admin -> com.ethic.messenger.ui.AdminScreen()
    }
}
