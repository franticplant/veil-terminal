package io.github.franticplant.veil.engine

/** JNI entry points of `libveil_jni.so` (crate `veil-jni`). Handles are opaque IDs, not pointers. */
internal object NativeEngine {
  init {
    System.loadLibrary("veil_jni")
  }

  /** Starts an engine keeping its state in [dataDir], and returns its handle. */
  @JvmStatic external fun open(engineVersion: String, dataDir: String): Long

  /** Throws IllegalStateException if the engine is closed. */
  @JvmStatic external fun send(handle: Long, msg: ByteArray)

  /** Null on timeout; throws IllegalStateException if the engine is closed. */
  @JvmStatic external fun recv(handle: Long, timeoutMs: Int): ByteArray?

  /** Stops the engine. Later calls with this handle throw. */
  @JvmStatic external fun close(handle: Long)
}

/** A [MessagePipe] to an engine running in this process. */
class NativePipe private constructor(private val handle: Long) : MessagePipe {
  @Volatile private var closed = false

  override fun send(msg: ByteArray) {
    try {
      NativeEngine.send(handle, msg)
    } catch (e: IllegalStateException) {
      throw PipeClosedException(e.message ?: "engine closed")
    }
  }

  override fun recv(timeoutMs: Int): ByteArray? {
    try {
      return NativeEngine.recv(handle, timeoutMs)
    } catch (e: IllegalStateException) {
      throw PipeClosedException(e.message ?: "engine closed")
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    NativeEngine.close(handle)
  }

  companion object {
    fun open(engineVersion: String, dataDir: String): NativePipe =
        NativePipe(NativeEngine.open(engineVersion, dataDir))
  }
}
