package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContracts

fun interface ActivityResultCallback<O> { fun onActivityResult(result: O) }

abstract class ActivityResultLauncher<I> {
    fun launch(input: I): Unit = TODO()
}

class PickVisualMediaRequest internal constructor()

fun PickVisualMediaRequest(
    mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
): PickVisualMediaRequest = TODO()
