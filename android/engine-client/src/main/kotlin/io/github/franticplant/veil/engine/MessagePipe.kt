package io.github.franticplant.veil.engine

/**
 * A bidirectional pipe of encoded engine-protocol messages: the only thing an [EngineConnection]
 * needs from whatever carries it. Today that is [NativePipe] (JNI into an in-process engine); later
 * a Binder or vsock pipe implements the same two calls.
 */
interface MessagePipe : AutoCloseable {
    /** Sends one encoded `ClientMsg`, blocking while the engine's inbound queue is full. */
    fun send(msg: ByteArray)

    /**
     * Waits up to [timeoutMs] for the next encoded `EngineMsg`. Returns null on timeout.
     *
     * @throws PipeClosedException once the engine side is gone.
     */
    fun recv(timeoutMs: Int): ByteArray?
}

class PipeClosedException(message: String) : Exception(message)
