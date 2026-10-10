package org.tensorflow.lite

// Compile-only stand-in for TensorFlow Lite's Java API (the real AAR is not on the offline path).
class Interpreter(model: java.nio.ByteBuffer, options: Options?) : AutoCloseable {
    class Options {
        fun setNumThreads(threads: Int): Options = TODO()
    }

    fun run(input: Any, output: Any): Unit = TODO()

    override fun close(): Unit = TODO()
}
