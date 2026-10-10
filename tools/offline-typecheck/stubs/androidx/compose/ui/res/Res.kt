package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.painter.Painter

@Composable @ReadOnlyComposable fun stringResource(id: Int): String = TODO()
@Composable @ReadOnlyComposable fun stringResource(id: Int, vararg formatArgs: Any): String = TODO()
@Composable @ReadOnlyComposable fun pluralStringResource(id: Int, count: Int): String = TODO()
@Composable @ReadOnlyComposable fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String = TODO()
@Composable fun painterResource(id: Int): Painter = TODO()
