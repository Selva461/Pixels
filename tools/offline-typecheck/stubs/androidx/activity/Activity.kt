// Compile-only stand-ins for androidx.activity (Google Maven is unreachable here). Signatures mirror activity 1.10.
package androidx.activity

import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

open class ComponentActivity : android.app.Activity() {
    fun <I, O> registerForActivityResult(contract: ActivityResultContract<I, O>, callback: ActivityResultCallback<O>): ActivityResultLauncher<I> = TODO()
    public override fun onNewIntent(intent: android.content.Intent) {}
}

class SystemBarStyle private constructor() {
    companion object {
        fun dark(scrim: Int): SystemBarStyle = TODO()
        fun auto(lightScrim: Int, darkScrim: Int): SystemBarStyle = TODO()
    }
}

fun ComponentActivity.enableEdgeToEdge(statusBarStyle: SystemBarStyle = TODO(), navigationBarStyle: SystemBarStyle = TODO()): Unit = TODO()

inline fun <reified VM : ViewModel> ComponentActivity.viewModels(noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null): Lazy<VM> = TODO()
