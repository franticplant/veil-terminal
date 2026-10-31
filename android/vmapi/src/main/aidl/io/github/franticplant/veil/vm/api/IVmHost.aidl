package io.github.franticplant.veil.vm.api;

import android.os.ParcelFileDescriptor;

/**
 * The narrow control surface the VM host exposes to the main app and devd. It
 * cannot start an arbitrary VM or payload: the implementation owns the fixed
 * Veil config.
 */
interface IVmHost {
    /** AVF classes and methods visible to the VM host package. */
    String dump();

    /** Runs the fixed vsock probe; returns a human-readable report. */
    String probe(boolean protectedVm);

    /**
     * Boots the fixed engine payload and returns two pipe ends: [0] writes to
     * the VM, [1] reads from it. The VM host proxies them to vsock.
     */
    ParcelFileDescriptor[] startEngine(
        boolean protectedVm,
        int memoryMiB,
        boolean diagnostics,
        in IBinder client
    );

    /** Relays an already-connected TCP socket to the guest broker. */
    void dial(String host, int port, in ParcelFileDescriptor tcp, long connectionId);

    /** Closes one relay after its session ends. */
    void closeDial(long connectionId);

    /** Stops the VM started by startEngine. */
    void stopEngine();

    /** Recent payload callbacks and VM log output, for diagnostics. */
    String engineLog();

    /** VM host process and active relay counters, as JSON. */
    String stats();

    /**
     * Runs a newline-separated diagnostic script in the fixed probe payload and
     * leaves that VM running. The payload is a development tool, not the
     * engine; it has no keys and no network.
     */
    String probeShell(boolean protectedVm, String commands);

    /** Stops the persistent probe VM started by probeShell. */
    String probeStop();

    /**
     * Boots the engine with an explicit guest layout ("monolith", "workers",
     * or "tenants"). Existing startEngine() keeps the monolith layout.
     */
    ParcelFileDescriptor[] startEngineWithLayout(
        boolean protectedVm,
        int memoryMiB,
        boolean diagnostics,
        String layout,
        in IBinder client
    );
}
