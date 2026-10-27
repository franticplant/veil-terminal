package io.github.franticplant.veil.engine;

// The engine in its own process. connect() starts an engine on one end of a
// new socket pair and returns the other end; the app speaks the engine
// protocol on it with veil-wire framing (StreamPipe).
interface IEngineProcess {
    ParcelFileDescriptor connect(String engineVersion, String dataDir);
}
