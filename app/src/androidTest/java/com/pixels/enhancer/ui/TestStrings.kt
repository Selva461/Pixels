package com.pixels.enhancer.ui

import androidx.test.platform.app.InstrumentationRegistry

private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** An app string as the user reads it, for finding nodes by their visible text. */
fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

fun plural(id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, *args)
