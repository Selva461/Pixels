package com.pixels.enhancer.ui

import com.pixels.enhancer.ui.editor.EditorActions
import java.lang.reflect.Proxy
import java.util.Collections

/** One call the screen made: action name and arguments. */
data class Call(val name: String, val args: List<Any?>)

/** An [EditorActions] that records every call instead of editing, so UI tests can assert on them. */
class RecordingActions {
    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    val actions: EditorActions = Proxy.newProxyInstance(EditorActions::class.java.classLoader, arrayOf(EditorActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RecordingActions"
            else -> {
                calls += Call(method.name, args.orEmpty().toList())
                null
            }
        }
    } as EditorActions

    fun called(name: String, vararg args: Any?): Boolean = synchronized(calls) { calls.any { it.name == name && (args.isEmpty() || it.args == args.toList()) } }
}
