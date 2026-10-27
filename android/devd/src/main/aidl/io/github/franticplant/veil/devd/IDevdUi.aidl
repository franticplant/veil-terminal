package io.github.franticplant.veil.devd;

// Between devd's screen (the :ui process) and its service (the main process).
// Not exported: only devd's own UI binds. JSON in and out, like DevHooks.
interface IDevdUi {
    String call(String method, String paramsJson);
}
