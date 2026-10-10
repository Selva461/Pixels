// Compile-only stand-ins for androidx.lifecycle 2.9 (Google Maven is unreachable here).
package androidx.lifecycle

import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

abstract class ViewModel {
    protected open fun onCleared() {}
}

val ViewModel.viewModelScope: CoroutineScope get() = TODO()

class ViewModelProvider {
    interface Factory {
        fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = TODO()
    }
}
