package androidx.lifecycle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

abstract class CreationExtras

class InitializerViewModelFactoryBuilder {
    inline fun <reified VM : ViewModel> addInitializer(noinline initializer: CreationExtras.() -> VM): Unit = TODO()
}

inline fun viewModelFactory(builder: InitializerViewModelFactoryBuilder.() -> Unit): ViewModelProvider.Factory = TODO()

inline fun <reified VM : ViewModel> InitializerViewModelFactoryBuilder.initializer(noinline initializer: CreationExtras.() -> VM): Unit = TODO()
