package com.pixels.enhancer

import android.app.Application
import android.content.Context
import com.pixels.enhancer.core.logging.LogcatLogger
import com.pixels.enhancer.data.decoder.AndroidFaceLocator
import com.pixels.enhancer.data.decoder.AndroidImageRepository
import com.pixels.enhancer.data.preferences.SharedPreferencesSettingsRepository
import com.pixels.enhancer.data.storage.MediaStoreImageSaver
import com.pixels.enhancer.data.storage.ShareCache
import com.pixels.enhancer.data.storage.ThumbnailStore
import com.pixels.enhancer.domain.project.FileProjectStore
import com.pixels.enhancer.domain.project.ProjectManager
import java.io.File
import com.pixels.enhancer.domain.analysis.StatisticalImageAnalyzer
import com.pixels.enhancer.domain.planning.NaturalEnhancementPlanner
import com.pixels.enhancer.domain.processing.PipelineImageProcessor
import com.pixels.enhancer.domain.processing.stages.DefaultPipeline
import com.pixels.enhancer.domain.repository.SettingsRepository
import com.pixels.enhancer.domain.usecase.EnhanceImageUseCase
import com.pixels.enhancer.domain.validation.NaturalOutputValidator

class PixelsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Composition root: the only place implementations are chosen. Swapping an analyzer, planner or
 * stage list happens here, never in UI code. Deliberately manual — a DI framework would add
 * more machinery than this small graph needs.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val isDebugBuild: Boolean = BuildConfig.DEBUG

    private val logger = LogcatLogger(verbose = isDebugBuild)

    val settingsRepository: SettingsRepository = SharedPreferencesSettingsRepository(appContext)

    val shareCache = ShareCache(appContext)

    val projectManager = ProjectManager(FileProjectStore(File(appContext.filesDir, "projects"), logger))

    val thumbnails = ThumbnailStore(File(appContext.filesDir, "thumbnails"))

    val enhanceImageUseCase = EnhanceImageUseCase(
        imageRepository = AndroidImageRepository(appContext.contentResolver),
        analyzer = StatisticalImageAnalyzer(),
        planner = NaturalEnhancementPlanner(),
        processor = PipelineImageProcessor(DefaultPipeline.stages(), logger = logger),
        validator = NaturalOutputValidator(),
        saver = MediaStoreImageSaver(appContext.contentResolver, logger),
        logger = logger,
        faceLocator = AndroidFaceLocator(),
    )
}
