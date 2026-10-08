package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest

abstract class ActivityResultContract<I, O>

class ActivityResultContracts private constructor() {
    open class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        sealed interface VisualMediaType
        object ImageOnly : VisualMediaType
        object ImageAndVideo : VisualMediaType
    }
    open class PickMultipleVisualMedia(maxItems: Int = 0) : ActivityResultContract<PickVisualMediaRequest, List<@JvmSuppressWildcards Uri>>()
    open class TakePicture : ActivityResultContract<Uri, Boolean>()
}
