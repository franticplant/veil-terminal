//! The Microdroid diagnostic payload.
//!
//! Microdroid calls `AVmPayload_main` after boot. The payload listens on
//! AF_VSOCK port 5000 and serves a small, line-oriented diagnostic shell. The
//! host side can send commands (`env`, `ps`, `exec ...`, `seccomp`, ...) over
//! that stream, so the guest can be probed from the API without rebuilding the
//! payload for every experiment.
//!
//! This is a development/diagnostic payload, not the engine. It is deliberately
//! separate from `libveil_vm_engine.so`; the engine never has a command shell.

// The vsock socket API is raw libc; the unsafe surface is confined to this
// crate, as with the JNI bridge.
#![allow(unsafe_code)]

use std::ffi::{CStr, CString};
use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::mem::size_of;
use std::os::fd::{FromRawFd, RawFd};
use std::process::Command;
use std::ptr::null_mut;

/// Linux's AF_VSOCK. libc does not expose it on every Android target.
const AF_VSOCK: i32 = 40;
/// Bind to any local CID; the host connects to the VM's assigned CID.
const VMADDR_CID_ANY: u32 = u32::MAX;
const VMADDR_CID_HOST: u32 = 2;
const PORT: u32 = 5000;
const END: &str = "\n__VEIL_PROBE_END__\n";
const CMD_END: &str = "\n__VEIL_CMD_END__\n";

#[repr(C)]
struct SockaddrVm {
    family: u16,
    reserved: u16,
    port: u32,
    cid: u32,
    zero: [u8; 4],
}

/// The payload library calls back into Microdroid's loader when its listener is
/// ready. dlsym keeps the crate linkable outside the VM (for tests and lint).
unsafe fn notify_ready() {
    let name = b"AVmPayload_notifyPayloadReady\0";
    let symbol = unsafe { libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr().cast()) };
    if !symbol.is_null() {
        let notify: unsafe extern "C" fn() = unsafe { std::mem::transmute(symbol) };
        unsafe { notify() };
    }
}

/// Entry point Microdroid runs in the guest.
#[unsafe(no_mangle)]
pub extern "C" fn AVmPayload_main() -> i32 {
    unsafe {
        let listener = libc::socket(AF_VSOCK, libc::SOCK_STREAM, 0);
        if listener < 0 {
            return 1;
        }
        let addr = SockaddrVm {
            family: AF_VSOCK as u16,
            reserved: 0,
            port: PORT,
            cid: VMADDR_CID_ANY,
            zero: [0; 4],
        };
        if libc::bind(
            listener,
            (&addr as *const SockaddrVm).cast(),
            size_of::<SockaddrVm>() as libc::socklen_t,
        ) != 0
        {
            libc::close(listener);
            return 2;
        }
        if libc::listen(listener, 4) != 0 {
            libc::close(listener);
            return 3;
        }
        notify_ready();

        loop {
            let connection = libc::accept(listener, null_mut(), null_mut());
            if connection < 0 {
                let e = std::io::Error::last_os_error();
                if e.raw_os_error() == Some(libc::EINTR) {
                    continue;
                }
                break;
            }
            let stream = File::from_raw_fd(connection);
            handle_connection(stream);
        }
        libc::close(listener);
    }
    0
}

fn handle_connection(stream: File) {
    let Ok(read_half) = stream.try_clone() else {
        return;
    };
    let reader = BufReader::new(read_half);
    let mut writer = stream;
    for line in reader.lines() {
        let Ok(line) = line else {
            break;
        };
        let command = line.trim();
        if command.is_empty() {
            continue;
        }
        let output = run_command(command);
        if writer.write_all(output.as_bytes()).is_err()
            || writer.write_all(CMD_END.as_bytes()).is_err()
            || writer.flush().is_err()
        {
            break;
        }
        if command == "quit" {
            break;
        }
    }
    let _ = writer.write_all(END.as_bytes());
    let _ = writer.flush();
}

fn run_command(command: &str) -> String {
    let (name, rest) = command
        .split_once(char::is_whitespace)
        .unwrap_or((command, ""));
    match name {
        "help" => help(),
        "env" => report(),
        "status" => status_lines(),
        "ps" => ps(),
        "mounts" => mounts(),
        "cgroup" => cgroup_report(),
        "fork" => format!("fork_child_exit={}\n", child_exit()),
        "exec" => exec_report(rest),
        "cat" => cat(rest),
        "ls" => ls(rest),
        "stat" => stat(rest),
        "readlink" => readlink(rest),
        "setuid" => setuid_report(rest),
        "prctl" => prctl_report(rest),
        "syscall" => syscall_report(rest),
        "dlopen" => dlopen_report(rest),
        "kill" => kill_report(rest),
        "unshare" => unshare_report(rest),
        "landlock" => format!("landlock_create={}\n", landlock_report()),
        "binder" => binder_report(),
        "vsock" => format!("vsock_connect_errno={}\n", vsock_connect_report()),
        "socketpair" => format!("{}\n", socketpair_report()),
        "seccomp" => seccomp_report(),
        "dumpable" => format!("pr_set_dumpable={}\n", unsafe {
            libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0)
        }),
        "mem" => mem(),
        "fds" => fds(),
        "threads" => threads(),
        "sleep" => {
            let ms = rest.trim().parse::<u64>().unwrap_or(100);
            std::thread::sleep(std::time::Duration::from_millis(ms.min(60_000)));
            format!("slept={ms}\n")
        }
        "echo" => format!("{rest}\n"),
        "quit" => "bye\n".to_string(),
        other => format!("unknown command: {other}; try help\n"),
    }
}

fn help() -> String {
    [
        "commands:",
        "  env                 identity, status, mounts, cgroup, syscall probes",
        "  status | ps | mem | fds | threads",
        "  mounts | cgroup",
        "  exec <shell cmd>    attempt /system/bin/sh -c; usually EACCES in Microdroid",
        "  cat <path> | ls [path] | stat <path> | readlink <path>",
        "  fork | seccomp | binder | vsock | socketpair | landlock | dumpable",
        "  setuid <uid> | unshare [user|ns] | prctl <option> [args...]",
        "  syscall <nr> [a0..a5] | dlopen <path> | kill <pid> <sig>",
        "  sleep <ms> | echo <text> | quit",
        "",
    ]
    .join("\n")
}

fn read(path: &str) -> String {
    std::fs::read_to_string(path).unwrap_or_else(|e| format!("<{e}>"))
}

fn cat(path: &str) -> String {
    let path = path.trim();
    if path.is_empty() {
        return "usage: cat <path>\n".to_string();
    }
    match std::fs::read(path) {
        Ok(bytes) => {
            let limit = 64 * 1024;
            let slice = &bytes[..bytes.len().min(limit)];
            let text = String::from_utf8_lossy(slice);
            if bytes.len() > limit {
                format!("{text}\n<truncated at {limit} bytes>\n")
            } else {
                format!("{text}\n")
            }
        }
        Err(e) => format!("cat {path}: {e}\n"),
    }
}

fn ls(path: &str) -> String {
    let path = if path.trim().is_empty() {
        "/"
    } else {
        path.trim()
    };
    match std::fs::read_dir(path) {
        Ok(entries) => {
            let mut names: Vec<String> = entries
                .flatten()
                .map(|e| {
                    let ty = e.file_type().map(|t| {
                        if t.is_dir() {
                            "d"
                        } else if t.is_symlink() {
                            "l"
                        } else {
                            "-"
                        }
                    });
                    format!("{} {}", ty.unwrap_or("?"), e.file_name().to_string_lossy())
                })
                .collect();
            names.sort();
            names.join("\n") + "\n"
        }
        Err(e) => format!("ls {path}: {e}\n"),
    }
}

fn stat(path: &str) -> String {
    let path = path.trim();
    match std::fs::metadata(path) {
        Ok(m) => format!(
            "len={} dir={} file={} readonly={} mode={:o}\n",
            m.len(),
            m.is_dir(),
            m.is_file(),
            m.permissions().readonly(),
            std::os::unix::fs::PermissionsExt::mode(&m.permissions())
        ),
        Err(e) => format!("stat {path}: {e}\n"),
    }
}

fn readlink(path: &str) -> String {
    let path = path.trim();
    match std::fs::read_link(path) {
        Ok(p) => format!("{} -> {}\n", path, p.display()),
        Err(e) => format!("readlink {path}: {e}\n"),
    }
}

fn ps() -> String {
    let mut out = String::new();
    match std::fs::read_dir("/proc") {
        Ok(entries) => {
            let mut rows: Vec<(u32, String)> = Vec::new();
            for entry in entries.flatten() {
                let name = entry.file_name();
                let Some(pid) = name.to_str().and_then(|s| s.parse::<u32>().ok()) else {
                    continue;
                };
                let cmdline = read(&format!("/proc/{pid}/cmdline"))
                    .replace('\0', " ")
                    .trim()
                    .to_string();
                if cmdline.is_empty() {
                    continue;
                }
                rows.push((pid, cmdline));
            }
            rows.sort();
            for (pid, cmdline) in rows {
                out.push_str(&format!("{pid} {cmdline}\n"));
            }
        }
        Err(e) => out.push_str(&format!("ps: {e}\n")),
    }
    out
}

fn status_lines() -> String {
    let status = read("/proc/self/status");
    let wanted = [
        "Name:",
        "Pid:",
        "PPid:",
        "Uid:",
        "Gid:",
        "Groups:",
        "CapInh:",
        "CapPrm:",
        "CapEff:",
        "CapBnd:",
        "NoNewPrivs:",
        "Seccomp:",
        "Seccomp_filters:",
        "NSpid:",
        "Cpus_allowed_list:",
        "Mems_allowed_list:",
    ];
    let mut out = String::new();
    for line in status.lines() {
        if wanted.iter().any(|w| line.starts_with(w)) {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

fn mem() -> String {
    let status = read("/proc/self/status");
    let mut out = String::new();
    for line in status.lines() {
        if line.starts_with("Vm") || line.starts_with("Rss") {
            out.push_str(line);
            out.push('\n');
        }
    }
    out.push_str(&read("/proc/meminfo"));
    out
}

fn fds() -> String {
    match std::fs::read_dir("/proc/self/fd") {
        Ok(entries) => {
            let mut out = String::new();
            for entry in entries.flatten() {
                let path = std::fs::read_link(entry.path())
                    .map(|p| p.display().to_string())
                    .unwrap_or_else(|e| format!("<{e}>"));
                out.push_str(&format!(
                    "{} -> {path}\n",
                    entry.file_name().to_string_lossy()
                ));
            }
            out
        }
        Err(e) => format!("fds: {e}\n"),
    }
}

fn threads() -> String {
    let mut out = String::new();
    match std::fs::read_dir("/proc/self/task") {
        Ok(entries) => {
            for entry in entries.flatten() {
                let name = entry.file_name();
                let comm = read(&format!("/proc/self/task/{}/comm", name.to_string_lossy()));
                out.push_str(&format!("{} {}\n", name.to_string_lossy(), comm.trim()));
            }
        }
        Err(e) => out.push_str(&format!("threads: {e}\n")),
    }
    out
}

fn mounts() -> String {
    let mut out = String::new();
    for line in read("/proc/self/mounts").lines() {
        if line.contains(" /sys/fs/cgroup ")
            || line.contains(" /dev ")
            || line.contains(" /proc ")
            || line.contains(" /data ")
            || line.contains(" /mnt/apk ")
            || line.contains(" /dev/pts ")
        {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

fn cgroup_report() -> String {
    let mut out = String::new();
    out.push_str("cgroup.controllers=");
    out.push_str(read("/sys/fs/cgroup/cgroup.controllers").trim());
    out.push('\n');
    out.push_str("cgroup.subtree_control=");
    out.push_str(read("/sys/fs/cgroup/cgroup.subtree_control").trim());
    out.push('\n');
    match std::fs::read_dir("/sys/fs/cgroup") {
        Ok(entries) => {
            let names: Vec<String> = entries
                .flatten()
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .take(80)
                .collect();
            out.push_str("cgroup.entries=");
            out.push_str(&names.join(","));
            out.push('\n');
        }
        Err(e) => out.push_str(&format!("cgroup.read_dir={e}\n")),
    }
    let dir = format!("/sys/fs/cgroup/veil-probe-{}", std::process::id());
    match std::fs::create_dir(&dir) {
        Ok(()) => {
            let write = std::fs::write(format!("{dir}/memory.max"), b"67108864\n");
            let read_back = std::fs::read_to_string(format!("{dir}/memory.max")).ok();
            let _ = std::fs::remove_dir(&dir);
            out.push_str(&format!(
                "cgroup.create=ok write_memory.max={write:?} read={read_back:?}\n"
            ));
        }
        Err(e) => out.push_str(&format!("cgroup.create={e}\n")),
    }
    out
}

fn child_exit() -> i32 {
    let pid = unsafe { libc::fork() };
    if pid == 0 {
        unsafe { libc::_exit(0) };
    }
    if pid < 0 {
        return -1;
    }
    let mut status = 0;
    let rc = unsafe { libc::waitpid(pid, &mut status, 0) };
    if rc == pid && libc::WIFEXITED(status) {
        libc::WEXITSTATUS(status)
    } else {
        -1
    }
}

fn child_errno(f: impl FnOnce() -> i32) -> i32 {
    let mut fds = [0; 2];
    if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        return -1;
    }
    let pid = unsafe { libc::fork() };
    if pid == 0 {
        unsafe { libc::close(fds[0]) };
        let rc = f();
        let bytes = rc.to_ne_bytes();
        unsafe {
            libc::write(fds[1], bytes.as_ptr().cast(), bytes.len());
            libc::_exit(0);
        }
    }
    unsafe { libc::close(fds[1]) };
    let mut bytes = [0u8; 4];
    let n = if pid > 0 {
        unsafe { libc::read(fds[0], bytes.as_mut_ptr().cast(), bytes.len()) }
    } else {
        -1
    };
    unsafe { libc::close(fds[0]) };
    if pid > 0 {
        let mut status = 0;
        unsafe { libc::waitpid(pid, &mut status, 0) };
    }
    if n == 4 {
        i32::from_ne_bytes(bytes)
    } else {
        -1
    }
}

fn errno_of(rc: i32) -> i32 {
    if rc == 0 {
        0
    } else {
        std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
    }
}

fn exec_report(command: &str) -> String {
    let command = command.trim();
    if command.is_empty() {
        return "usage: exec <shell command>\n".to_string();
    }
    match Command::new("/system/bin/sh")
        .args(["-c", command])
        .output()
    {
        Ok(out) => format!(
            "ok status={:?}\nstdout:\n{}\nstderr:\n{}\n",
            out.status.code(),
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        ),
        Err(e) => format!("err {e}\n"),
    }
}

fn parse_num(text: &str) -> Option<u64> {
    let text = text.trim();
    if let Some(hex) = text.strip_prefix("0x") {
        u64::from_str_radix(hex, 16).ok()
    } else {
        text.parse::<u64>().ok()
    }
}

fn setuid_report(rest: &str) -> String {
    let Some(uid) = parse_num(rest.trim()) else {
        return "usage: setuid <uid>\n".to_string();
    };
    let rc = child_errno(|| errno_of(unsafe { libc::setuid(uid as libc::uid_t) }));
    format!("setuid({uid})={rc}\n")
}

fn prctl_report(rest: &str) -> String {
    let mut parts = rest.split_whitespace();
    let Some(option) = parts.next().and_then(parse_num) else {
        return "usage: prctl <option> [arg0..arg3]\n".to_string();
    };
    let mut args = [0u64; 4];
    for (i, value) in parts.take(4).enumerate() {
        args[i] = parse_num(value).unwrap_or(0);
    }
    let rc = unsafe {
        libc::prctl(
            option as libc::c_int,
            args[0] as libc::c_ulong,
            args[1] as libc::c_ulong,
            args[2] as libc::c_ulong,
            args[3] as libc::c_ulong,
        )
    };
    let errno = if rc == -1 {
        std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
    } else {
        0
    };
    format!("prctl({option}, {args:?})={rc} errno={errno}\n")
}

fn syscall_report(rest: &str) -> String {
    let mut parts = rest.split_whitespace();
    let Some(nr) = parts.next().and_then(parse_num) else {
        return "usage: syscall <nr> [a0..a5]\n".to_string();
    };
    let mut args = [0u64; 6];
    for (i, value) in parts.take(6).enumerate() {
        args[i] = parse_num(value).unwrap_or(0);
    }
    let ret = unsafe {
        libc::syscall(
            nr as libc::c_long,
            args[0],
            args[1],
            args[2],
            args[3],
            args[4],
            args[5],
        )
    };
    let errno = if ret == -1 {
        std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
    } else {
        0
    };
    format!("syscall({nr}, {args:?})={ret} errno={errno}\n")
}

fn dlopen_report(rest: &str) -> String {
    let path = rest.trim();
    if path.is_empty() {
        return "usage: dlopen <path>\n".to_string();
    }
    let Ok(cpath) = CString::new(path) else {
        return "dlopen: path contains NUL\n".to_string();
    };
    let handle = unsafe { libc::dlopen(cpath.as_ptr(), libc::RTLD_NOW) };
    if handle.is_null() {
        let err = unsafe { libc::dlerror() };
        if err.is_null() {
            "dlopen=failed\n".to_string()
        } else {
            format!(
                "dlopen=failed {}\n",
                unsafe { CStr::from_ptr(err) }.to_string_lossy()
            )
        }
    } else {
        unsafe { libc::dlclose(handle) };
        "dlopen=ok\n".to_string()
    }
}

fn kill_report(rest: &str) -> String {
    let mut parts = rest.split_whitespace();
    let (Some(pid), Some(sig)) = (
        parts.next().and_then(parse_num),
        parts.next().and_then(parse_num),
    ) else {
        return "usage: kill <pid> <sig>\n".to_string();
    };
    let rc = unsafe { libc::kill(pid as libc::pid_t, sig as libc::c_int) };
    let errno = if rc != 0 {
        std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
    } else {
        0
    };
    format!("kill({pid}, {sig})={rc} errno={errno}\n")
}

fn unshare_report(rest: &str) -> String {
    let user = rest.trim() == "user";
    if user {
        format!(
            "unshare_user={}\n",
            child_errno(|| errno_of(unsafe { libc::unshare(libc::CLONE_NEWUSER) }))
        )
    } else {
        format!(
            "unshare_ns={}\n",
            child_errno(|| {
                errno_of(unsafe {
                    libc::unshare(libc::CLONE_NEWNS | libc::CLONE_NEWPID | libc::CLONE_NEWNET)
                })
            })
        )
    }
}

fn binder_report() -> String {
    let mut out = String::new();
    for path in ["/dev/binder", "/dev/hwbinder", "/dev/vndbinder"] {
        let c = CString::new(path).unwrap();
        let fd = unsafe { libc::open(c.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
        if fd >= 0 {
            out.push_str(&format!("{path}=open\n"));
            unsafe { libc::close(fd) };
        } else {
            out.push_str(&format!("{path}=err {}\n", std::io::Error::last_os_error()));
        }
    }
    out
}

fn vsock_connect_report() -> i32 {
    unsafe {
        let fd = libc::socket(AF_VSOCK, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 {
            return -1;
        }
        let addr = SockaddrVm {
            family: AF_VSOCK as u16,
            reserved: 0,
            port: 1,
            cid: VMADDR_CID_HOST,
            zero: [0; 4],
        };
        let rc = libc::connect(
            fd,
            (&addr as *const SockaddrVm).cast(),
            size_of::<SockaddrVm>() as libc::socklen_t,
        );
        let errno = if rc == 0 {
            0
        } else {
            std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
        };
        libc::close(fd);
        errno
    }
}

fn socketpair_report() -> String {
    let mut fds = [0; 2];
    if unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, fds.as_mut_ptr()) } != 0 {
        return format!("socketpair=err {}", std::io::Error::last_os_error());
    }
    let byte = *b"x";
    let w = unsafe { libc::write(fds[0], (&byte as *const u8).cast(), 1) };
    let mut got = [0u8; 1];
    let r = unsafe { libc::read(fds[1], got.as_mut_ptr().cast(), 1) };
    unsafe {
        libc::close(fds[0]);
        libc::close(fds[1]);
    }
    format!("socketpair=w{w} r{r} byte={:?}", got[0] as char)
}

fn seccomp_report() -> String {
    let before = unsafe { libc::prctl(libc::PR_GET_SECCOMP, 0, 0, 0, 0) };
    let mut out = format!("pr_get_seccomp={before}\n");
    let child = child_errno(|| unsafe {
        let nnp = libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        if nnp != 0 {
            return -libc::PR_SET_NO_NEW_PRIVS;
        }
        #[cfg(target_arch = "aarch64")]
        const GETPID: u32 = 172;
        #[cfg(target_arch = "x86_64")]
        const GETPID: u32 = 39;
        #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
        const GETPID: u32 = 0;

        const BPF_LD: u16 = 0x00;
        const BPF_W: u16 = 0x00;
        const BPF_ABS: u16 = 0x20;
        const BPF_JMP: u16 = 0x05;
        const BPF_JEQ: u16 = 0x10;
        const BPF_K: u16 = 0x00;
        const BPF_RET: u16 = 0x06;
        const SECCOMP_RET_ERRNO: u32 = 0x0005_0000;
        const SECCOMP_RET_ALLOW: u32 = 0x7fff_0000;
        let mut filter = [
            libc::sock_filter {
                code: BPF_LD | BPF_W | BPF_ABS,
                jt: 0,
                jf: 0,
                k: 0,
            },
            libc::sock_filter {
                code: BPF_JMP | BPF_JEQ | BPF_K,
                jt: 0,
                jf: 1,
                k: GETPID,
            },
            libc::sock_filter {
                code: BPF_RET | BPF_K,
                jt: 0,
                jf: 0,
                k: SECCOMP_RET_ERRNO | libc::EPERM as u32,
            },
            libc::sock_filter {
                code: BPF_RET | BPF_K,
                jt: 0,
                jf: 0,
                k: SECCOMP_RET_ALLOW,
            },
        ];
        let mut program = libc::sock_fprog {
            len: filter.len() as u16,
            filter: filter.as_mut_ptr(),
        };
        let rc = libc::prctl(
            libc::PR_SET_SECCOMP,
            libc::SECCOMP_MODE_FILTER,
            &mut program as *mut libc::sock_fprog,
            0,
            0,
        );
        if rc != 0 {
            return -libc::PR_SET_SECCOMP;
        }
        let pid = libc::syscall(GETPID as libc::c_long) as i32;
        if pid == -1 {
            -std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
        } else {
            pid
        }
    });
    out.push_str(&format!("seccomp_child_result={child}\n"));
    out
}

fn landlock_report() -> String {
    let rc = unsafe { libc::syscall(444, null_mut::<libc::c_void>(), 0usize, 0u32) as i32 };
    if rc >= 0 {
        unsafe { libc::close(rc) };
        "ok".to_string()
    } else {
        std::io::Error::last_os_error().to_string()
    }
}

fn report() -> String {
    let mut out = String::new();
    out.push_str("veil-vm-probe environment\n");
    out.push_str(&format!(
        "uid={} euid={} gid={} egid={} pid={} ppid={}\n",
        unsafe { libc::getuid() },
        unsafe { libc::geteuid() },
        unsafe { libc::getgid() },
        unsafe { libc::getegid() },
        std::process::id(),
        unsafe { libc::getppid() }
    ));
    out.push_str(&format!(
        "selinux={:?}\n",
        read("/proc/self/attr/current").trim()
    ));
    out.push_str("status:\n");
    out.push_str(&status_lines());
    out.push_str("version=");
    out.push_str(read("/proc/version").trim());
    out.push('\n');
    out.push_str("cmdline=");
    out.push_str(read("/proc/cmdline").trim());
    out.push('\n');
    out.push_str("mounts:\n");
    out.push_str(&mounts());
    out.push_str("cgroup=/");
    out.push_str(read("/proc/self/cgroup").trim());
    out.push('\n');
    out.push_str(&cgroup_report());
    out.push_str("ps:\n");
    out.push_str(&ps());
    out.push_str(&format!("fork_child_exit={}\n", child_exit()));
    out.push_str(&format!(
        "setuid_6001={}\n",
        child_errno(|| errno_of(unsafe { libc::setuid(6001) }))
    ));
    out.push_str(&format!(
        "unshare_user={}\n",
        child_errno(|| errno_of(unsafe { libc::unshare(libc::CLONE_NEWUSER) }))
    ));
    out.push_str(&format!(
        "unshare_ns={}\n",
        child_errno(|| {
            errno_of(unsafe {
                libc::unshare(libc::CLONE_NEWNS | libc::CLONE_NEWPID | libc::CLONE_NEWNET)
            })
        })
    ));
    out.push_str(&format!("landlock_create={}\n", landlock_report()));
    out.push_str(&binder_report());
    out.push_str(&format!("vsock_connect_errno={}\n", vsock_connect_report()));
    out.push_str(&format!("{}\n", socketpair_report()));
    out.push_str(&seccomp_report());
    out.push_str(&format!("pr_set_dumpable={}\n", unsafe {
        libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0)
    }));
    out
}

// Keep RawFd referenced for the SCM_RIGHTS extension point without pulling in a
// platform-specific helper today.
#[allow(dead_code)]
fn fd_raw(file: &File) -> RawFd {
    use std::os::fd::AsRawFd;
    file.as_raw_fd()
}
