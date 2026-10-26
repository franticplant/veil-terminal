package io.github.franticplant.veil.dev.api;

// What the Veil app's dev flavor exposes to the dev agent (devd). JSON in and
// out: the method catalog and the calls are defined by MethodSpec, so adding a
// method does not change this interface. Only the signature-protected
// DEV_HOOKS permission can bind to it, and only dev builds contain it.
interface IDevHooks {
    // A JSON array of MethodSpec objects.
    String catalog();

    // Runs one method. paramsJson has already been checked against the
    // method's spec by the caller and is checked again here. Returns
    // {"result": ...} or {"error": {"code": "...", "message": "..."}}.
    String call(String method, String paramsJson);
}
