package com.ethic.messenger

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.Repo
import com.ethic.messenger.media.Sounds

class EthicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Sounds.init(this)
        Notifications.init(this)
        CallManager.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { Repo.foreground = true; Repo.onForeground() }
            override fun onStop(owner: LifecycleOwner) { Repo.foreground = false }
        })
    }
}
