package com.jqz.camerademojqz.app

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

/**
 * author：JQZ
 * createTime：2022/4/11  22:28
 */
class AppApplication : Application(), CameraXConfig.Provider {
    override fun onCreate() {
        super.onCreate()
    }
    //将 CameraX 日志记录级别设置为 Log.ERROR 以避免过多的 logcat 消息。
    // 情参考https:developer.android.comreferenceandroidxcameracoreCameraXConfig.BuildersetMinimumLoggingLevel(int)。
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
}