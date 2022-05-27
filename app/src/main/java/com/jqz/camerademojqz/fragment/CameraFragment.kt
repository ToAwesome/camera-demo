package com.jqz.camerademojqz.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.R
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.jqz.camerademojqz.*
import com.jqz.camerademojqz.databinding.CameraUiContainerBinding
import com.jqz.camerademojqz.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit
/**
 * author：JQZ
 * createTime：2022/4/14  22:15
 */
class CameraFragment : Fragment() {


    private var camera: Camera? = null

    private var displayId: Int = -1
    private lateinit var outputDirectory: File

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val mBinding get() = _fragmentCameraBinding!!

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var broadcastManager: LocalBroadcastManager
    private var cameraUiContainerBinding: CameraUiContainerBinding? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var windowManager: WindowManager
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private var cameraProvider: ProcessCameraProvider? = null

    private var preview: Preview? = null

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /**用于触发快门的音量减小按钮接收器*/
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 确保所有权限仍然存在，因为用户可能在应用程序处于暂停状态时删除了它们。
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), com.jqz.camerademojqz.R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _fragmentCameraBinding = null
        // 关闭我们的后台执行器
        cameraExecutor.shutdown()

        // 注销广播接收器和侦听器
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //初始化后台执行器
        cameraExecutor = Executors.newSingleThreadExecutor()
        broadcastManager = LocalBroadcastManager.getInstance(view.context)
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }

        //设置从我们的activity接收事件的意图过滤器
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        //每次设备的方向发生变化时，更新用例的旋转
        displayManager.registerDisplayListener(displayListener, null)

        //初始化 WindowManager 以检索显示指标
        windowManager = WindowManager(view.context)

        //确定输出目录
        outputDirectory = MainActivity.getOutputDirectory(requireContext())


        //等待视图正确布局
        mBinding.viewFinder.post {
            //跟踪附加此视图的显示
            displayId = mBinding.viewFinder.display.displayId
            // 构建 UI 控件
            updateCameraUi()

            // 设置相机及其用例
            setUpCamera()
        }

    }

    /**
     * 在配置更改时膨胀相机控件并手动更新 UI，
     * 以避免从视图层次结构中删除和重新添加取景器；这在支持它的设备上提供了无缝的旋转过渡。
     * 注意：从 Android 8 开始支持该标志，但对于运行 Android 9 或更低版本的设备，屏幕上仍有小闪烁。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        //使用更新的显示指标重新绑定相机
        bindCameraUseCases()
        // 启用或禁用相机之间的切换
        updateCameraSwitchButton()
    }

    // 初始化CameraX，准备绑定相机用例
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // 根据可用的相机选择 lensFacing
            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // 启用或禁用相机之间的切换
            updateCameraSwitchButton()

            // 构建和绑定相机用例
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**根据可用的相机启用或禁用按钮以切换相机*/
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }

    /**如果设备有可用的后置摄像头，则返回 true。否则为 false*/
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /**如果设备有可用的前置摄像头，则返回 true。否则为 false*/
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     *用于重新绘制相机 UI 控件的方法，每次配置更改时调用。
     */
    private fun updateCameraUi() {
        cameraUiContainerBinding?.root?.let {
            mBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            mBinding.root,
            true
        )

        //在后台，为画廊缩略图加载最新拍摄的照片（如果有）
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        //用于捕获照片的按钮的侦听器
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {

            //获取可修改图像捕获用例的稳定参考
            imageCapture?.let { imageCapture ->
                //创建输出文件以保存图像
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // 设置图像捕获元数据
                val metadata = ImageCapture.Metadata().apply {

                    // 使用前置摄像头时镜像
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }

                // 创建包含文件 + 元数据的输出选项对象
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                // 设置在拍摄照片后触发的图像捕获侦听器
                imageCapture.takePicture(
                    outputOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            Log.d(TAG, "照片拍摄成功：$savedUri")

                            // 我们只能使用 API 级别 23+ API 更改前景 Drawable
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                // 使用最新拍摄的照片更新图库缩略图
                                setGalleryThumbnail(savedUri)
                            }

                            // 对于运行 API 级别 >= 24 的设备，隐式广播将被忽略，因此如果您只针对 API 级别 24+，您可以删除此语句
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                                requireActivity().sendBroadcast(
                                    Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                                )
                            }

                            // 如果选择的文件夹是外部媒体目录，则这是不必要的，否则其他应用程序将无法访问我们的图像，
                            // 除非我们使用 [MediaScannerConnection] 扫描它们
                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                            ) { _, uri ->
                                Log.d(TAG, "扫描到媒体存储的图像捕获：$uri")
                            }
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "照片拍摄失败：${exc.message}", exc)
                        }

                    })

                //我们只能使用 API 级别 23+ API 更改前景 Drawable
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // 显示 Flash 动画以指示已拍摄照片
                    mBinding.root.postDelayed({
                        mBinding.root.foreground = ColorDrawable(Color.WHITE)
                        mBinding.root.postDelayed(
                            { mBinding.root.foreground = null }, ANIMATION_FAST_MILLIS
                        )
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }

        // 设置用于切换相机的按钮

        cameraUiContainerBinding?.cameraSwitchButton?.let {

            //禁用该按钮，直到设置好相机
            it.isEnabled = false


            // 用于切换摄像机的按钮的监听器。仅在启用按钮时调用
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // 重新绑定用例以更新选定的相机
                bindCameraUseCases()
            }
        }

        // 用于查看最近照片的按钮的监听器
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // 仅在画廊有照片时导航
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                Navigation.findNavController(
                    requireActivity(), com.jqz.camerademojqz.R.id.fragment_container
                ).navigate(CameraFragmentDirections
                    .actionCameraToGallery(outputDirectory.absolutePath))
            }
        }

    }

    //声明和绑定预览、捕获和分析用例
    private fun bindCameraUseCases() {
        // 获取用于将相机设置为全屏分辨率的屏幕指标
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "屏幕指标：${metrics.width()} x ${metrics.height()}")


        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "预览宽高比：$screenAspectRatio")

        val rotation = mBinding.viewFinder.display.rotation

        val cameraProvider = cameraProvider ?: throw IllegalStateException("相机初始化失败")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = Preview.Builder()
            // 我们要求纵横比但没有分辨率
            .setTargetAspectRatio(screenAspectRatio)
            //设置初始目标旋转
            .setTargetRotation(rotation)
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // 我们要求纵横比但没有分辨率以匹配预览配置，但让 CameraX 优化最适合我们用例的任何特定分辨率
            .setTargetAspectRatio(screenAspectRatio)
            //设置初始目标轮换，如果轮换在此用例的生命周期内发生变化，我们将不得不再次调用它
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            //我们要求纵横比但没有分辨率
            .setTargetAspectRatio(screenAspectRatio)
            // 设置初始目标轮换，如果轮换在此用例的生命周期内发生变化，我们将不得不再次调用它
            .setTargetRotation(rotation)
            .build()
            // 然后可以将分析器分配给实例
            .also {
                it.setAnalyzer(cameraExecutor,LuminosityAnalyzer{
                    // 从我们的分析器返回的值被传递给附加的侦听器我们在这里记录图像分析结果 - 你应该做一些有用的事情！
                    Log.d(TAG, "Average luminosity: $it")
                })
            }


        // 必须在重新绑定之前取消绑定用例
        cameraProvider.unbindAll()

        try {
            // 可以在此处传递可变数量的用例 - 相机提供对 CameraControl 和 CameraInfo 的访问
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalyzer)

            // 附加取景器的表面提供程序以预览用例
            preview?.setSurfaceProvider(mBinding.viewFinder.surfaceProvider)

            observeCameraState(camera?.cameraInfo!!)
        } catch (exc: Exception) {
            Log.e(TAG, "用例绑定失败", exc)
        }
    }


    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            run {
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // 要求用户关闭其他相机应用
                        Toast.makeText(context,
                            "CameraState: Pending Open",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.OPENING -> {
                        // 显示相机 UI
                        Toast.makeText(context,
                            "CameraState: Opening",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.OPEN -> {
                        // 设置相机资源并开始处理
                        Toast.makeText(context,
                            "CameraState: Open",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.CLOSING -> {
                        // 关闭相机界面
                        Toast.makeText(context,
                            "CameraState: Closing",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                        Toast.makeText(context,
                            "CameraState: Closed",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }

            cameraState.error?.let { error ->
                when (error.code) {
                    // 打开错误
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // 确保正确设置用例
                        Toast.makeText(context,
                            "Stream config error",
                            Toast.LENGTH_SHORT).show()
                    }
                    // 打开错误
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // 关闭相机或要求用户关闭另一个正在使用相机的相机应用
                        Toast.makeText(context,
                            "Camera in use",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // 关闭应用中另一个打开的相机，或要求用户关闭另一个正在使用相机的相机应用
                        Toast.makeText(context,
                            "Max cameras in use",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(context,
                            "Other recoverable error",
                            Toast.LENGTH_SHORT).show()
                    }
                    // 关闭错误
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        //要求用户启用设备的摄像头
                        Toast.makeText(context,
                            "Camera disabled",
                            Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // 要求用户重启设备以恢复摄像头功能
                        Toast.makeText(context,
                            "Fatal error",
                            Toast.LENGTH_SHORT).show()
                    }
                    // 关闭的错误
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // 要求用户禁用“请勿打扰”模式，然后重新打开相机
                        Toast.makeText(context,
                            "Do not disturb mode enabled",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] 需要枚举值
     *  [androidx.camera.core.AspectRatio].目前它的值为 4:3 和 16:9。
     *
     *  通过将预览比率的绝对值计算为提供的值之一，检测@params 中提供的尺寸的最合适比率。
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    private fun setGalleryThumbnail(uri: Uri) {
        // 在视图的线程中运行操作
        cameraUiContainerBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {
                // 删除缩略图填充
                photoViewButton.setPadding(resources.getDimension(com.jqz.camerademojqz.R.dimen.stroke_small).toInt())

                // 使用 Glide 将缩略图加载到圆形按钮中
                Glide.with(photoViewButton)
                    .load(uri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }

    /**
     * 对于不触发配置更改的方向更改，我们需要一个显示侦听器，例如，如果我们选择覆盖清单中的配置更改或 180 度方向更改
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d("相机", "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }


    /**
     * 我们的自定义图像分析类。
     * <p>我们需要做的就是用我们想要的操作覆盖函数 `analyze`。在这里，我们通过查看 YUV 帧的 Y 平面来计算图像的平均亮度。
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * 用于添加将在计算每个亮度时调用的侦听器
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * 用于从图像平面缓冲区中提取字节数组的辅助扩展函数
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // 将缓冲区倒回零
            val data = ByteArray(remaining())
            get(data)   // 将缓冲区复制到字节数组中
            return data // 返回字节数组
        }

        /**
         * 分析图像以产生结果。
         * <p>调用者负责确保可以足够快地执行此分析方法，以防止图像采集管道中的停顿。否则，将不会获取和分析新的可用图像。
         * <p>该方法返回后，传递给该方法的图像无效。调用者不应存储对此图像的外部引用，因为这些引用将变得无效。
         *
         * @param image 正在分析的图像非常重要：分析器方法实现必须在完成使用接收到的图像时调用 image.close()。
         * 否则，根据背压设置，可能无法接收到新图像或相机可能会停转。
         */
        override fun analyze(image: ImageProxy) {
            // 如果没有附加监听器，我们不需要进行分析
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // 跟踪分析的帧
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // 使用移动平均计算 FPS
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // 分析可能需要任意长的时间由于我们在不同的线程中运行，它不会拖延其他用例

            lastAnalyzedTimestamp = frameTimestamps.first

            // 由于 ImageAnalysis 中的格式为 YUV，因此 image.planes[0] 包含亮度平面
            val buffer = image.planes[0].buffer

            // 从回调对象中提取图像数据
            val data = buffer.toByteArray()

            // 将数据转换为范围为 0-255 的像素值数组
            val pixels = data.map { it.toInt() and 0xFF }

            // 计算图像的平均亮度
            val luma = pixels.average()

            // 用新值调用所有侦听器
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    companion object {

        private const val TAG = "相机"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /**用于创建时间戳文件的辅助函数*/
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(
                baseFolder, SimpleDateFormat(format, Locale.US)
                    .format(System.currentTimeMillis()) + extension
            )
    }
}