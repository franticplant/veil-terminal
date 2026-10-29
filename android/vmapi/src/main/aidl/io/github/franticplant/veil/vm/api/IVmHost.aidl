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
    ParcelFileDescriptor[] startEngine(boolean protectedVm, int memoryMiB, boolean diagnostics);

    /** Relays an already-connected TCP socket to the guest broker. */
    void dial(String host, int port, in ParcelFileDescriptor tcp);

    /** Stops the VM started by startEngine. */
    void stopEngine();

    /** Recent payload callbacks and VM log output, for diagnostics. */
    String engineLog();
}
