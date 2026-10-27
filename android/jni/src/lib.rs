//! JNI entry points for `io.github.franticplant.veil.engine.NativeEngine`.
//!
//! This is a byte pipe and nothing more: encoded protocol messages in, encoded
//! protocol messages out. It holds no protocol logic, so replacing JNI with
//! Binder or vsock later replaces only this crate.
//!
//! Java sees engines as opaque `long` handles. They are keys into a registry,
//! never pointers, so a stale or invented handle is an exception, not memory
//! corruption. Every entry point catches panics (`with_env`) and turns errors
//! into Java exceptions; nothing unwinds into the JVM.

// The JNI symbols must be exported unmangled, which edition 2024 spells
// `#[unsafe(no_mangle)]`. That attribute is the only unsafe code here.
#![allow(unsafe_code)]

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JString};
use jni::strings::JNIString;
use jni::sys::{jint, jlong};
use jni::{Env, EnvUnowned, errors::ThrowRuntimeExAndDefault, jni_str};
use veil_engine::{Engine, EngineConfig, Recv};

static ENGINES: LazyLock<Mutex<HashMap<jlong, Arc<Engine>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

/// The longest a single `recv` may block, whatever Java asks for.
const MAX_RECV_WAIT: Duration = Duration::from_secs(10);

fn registry() -> std::sync::MutexGuard<'static, HashMap<jlong, Arc<Engine>>> {
    // Poisoning only means a panic happened while the map was locked; the map
    // itself is never left half-updated by our code.
    ENGINES.lock().unwrap_or_else(|e| e.into_inner())
}

fn lookup(handle: jlong) -> Option<Arc<Engine>> {
    registry().get(&handle).cloned()
}

/// Throws `IllegalStateException(msg)` and returns `T::default()` to Java.
fn throw_closed<T: Default>(env: &mut Env, msg: String) -> jni::errors::Result<T> {
    env.throw_new(
        jni_str!("java/lang/IllegalStateException"),
        JNIString::from(msg),
    )?;
    Ok(T::default())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_engine_NativeEngine_open<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    engine_version: JString<'caller>,
    data_dir: JString<'caller>,
) -> jlong {
    unowned
        .with_env(|env| -> jni::errors::Result<jlong> {
            let version = engine_version.try_to_string(env)?;
            let data_dir = data_dir.try_to_string(env)?;
            let config = EngineConfig {
                version,
                data_dir: Some(data_dir.into()),
                dialer: None,
                host_keys: None,
            };
            let engine = match Engine::start(config) {
                Ok(engine) => engine,
                Err(e) => return throw_closed(env, e.to_string()),
            };
            let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
            registry().insert(handle, Arc::new(engine));
            Ok(handle)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_engine_NativeEngine_send<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    msg: JByteArray<'caller>,
) {
    unowned
        .with_env(|env| -> jni::errors::Result<()> {
            let Some(engine) = lookup(handle) else {
                return throw_closed(env, format!("no engine with handle {handle}"));
            };
            let bytes = env.convert_byte_array(&msg)?;
            if let Err(e) = engine.send(bytes) {
                return throw_closed(env, e.to_string());
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_engine_NativeEngine_recv<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    timeout_ms: jint,
) -> JByteArray<'caller> {
    unowned
        .with_env(|env| -> jni::errors::Result<JByteArray<'caller>> {
            let Some(engine) = lookup(handle) else {
                return throw_closed(env, format!("no engine with handle {handle}"));
            };
            let wait = Duration::from_millis(timeout_ms.max(0) as u64).min(MAX_RECV_WAIT);
            match engine.recv(wait) {
                Recv::Message(bytes) => env.byte_array_from_slice(&bytes),
                // null: nothing arrived in time.
                Recv::Timeout => Ok(JByteArray::default()),
                Recv::Closed => throw_closed(env, "engine closed".into()),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_engine_NativeEngine_close<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    unowned
        .with_env(|_env| -> jni::errors::Result<()> {
            let removed = registry().remove(&handle);
            // If a send or recv is still running on another thread it holds a
            // clone; the engine then stops when that call returns and drops it.
            if let Some(engine) = removed.and_then(|e| Arc::try_unwrap(e).ok()) {
                engine.shutdown();
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Serves an engine on the stream socket `fd` until the other end closes it,
/// blocking the calling thread. This is the engine side of a separate engine
/// process: the app holds the other end of the socket pair (`StreamPipe`).
/// Takes ownership of `fd`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_franticplant_veil_engine_NativeEngine_serveFd<'caller>(
    mut unowned: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    fd: jint,
    engine_version: JString<'caller>,
    data_dir: JString<'caller>,
) {
    unowned
        .with_env(|env| -> jni::errors::Result<()> {
            let version = engine_version.try_to_string(env)?;
            let data_dir = data_dir.try_to_string(env)?;
            use std::os::fd::FromRawFd;
            // SAFETY: Kotlin detached this descriptor from its
            // ParcelFileDescriptor and hands its ownership to us; nothing else
            // closes or uses it.
            let sock = unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) };
            let reader = match sock.try_clone() {
                Ok(r) => r,
                Err(e) => return throw_closed(env, format!("cannot use the socket: {e}")),
            };
            let config = EngineConfig {
                version,
                data_dir: Some(data_dir.into()),
                dialer: None,
                host_keys: None,
            };
            if let Err(e) = veil_engine_host::serve(config, reader, sock) {
                return throw_closed(env, format!("engine stream ended: {e}"));
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
