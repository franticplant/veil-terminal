//! Process limits for the native engine host.
//!
//! The engine already runs in its own process, but same-UID processes still
//! share the app's authority. These limits shrink the damage a bug can do:
//! bounded address space and file descriptors, no core dumps, no ptrace, and
//! no new privileges. They are applied before the engine serves any message.

#![allow(unsafe_code)]

/// Applies the limits. Returns human-readable warnings for anything the kernel
/// refused; startup continues, because some Android versions restrict a few of
/// these for app processes.
pub fn apply(memory_mb: u64, nofile: u64) -> Vec<String> {
    let mut warnings = Vec::new();

    set(
        &mut warnings,
        libc::RLIMIT_NOFILE as libc::c_int,
        nofile,
        nofile,
        "nofile",
    );
    set(
        &mut warnings,
        libc::RLIMIT_CORE as libc::c_int,
        0,
        0,
        "core",
    );
    if memory_mb > 0 {
        let bytes = memory_mb.saturating_mul(1024 * 1024);
        set(
            &mut warnings,
            libc::RLIMIT_AS as libc::c_int,
            bytes,
            bytes,
            "address space",
        );
    }

    let rc = unsafe { libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) };
    if rc != 0 {
        warnings.push(format!("no_new_privs: {}", std::io::Error::last_os_error()));
    }
    let rc = unsafe { libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0) };
    if rc != 0 {
        warnings.push(format!("dumpable: {}", std::io::Error::last_os_error()));
    }
    if let Err(e) = seccomp() {
        warnings.push(format!("seccomp: {e}"));
    }
    warnings
}

/// A deny-list filter for operations the engine never needs after startup:
/// executing programs, tracing, mounting, changing credentials, and creating
/// namespaces. It is deliberately a deny-list, not a full allow-list: the
/// syscall surface of tokio and russh is large and version-dependent.
fn seccomp() -> Result<(), String> {
    #[cfg(target_arch = "aarch64")]
    const DENY: &[u32] = &[
        221, // execve
        281, // execveat
        117, // ptrace
        40,  // mount
        39,  // umount2
        41,  // pivot_root
        51,  // chroot
        146, // setuid
        144, // setgid
        147, // setresuid
        149, // setresgid
        91,  // capset
        97,  // unshare
    ];
    #[cfg(target_arch = "x86_64")]
    const DENY: &[u32] = &[
        59,  // execve
        322, // execveat
        101, // ptrace
        165, // mount
        166, // umount2
        155, // pivot_root
        161, // chroot
        105, // setuid
        106, // setgid
        117, // setresuid
        119, // setresgid
        126, // capset
        272, // unshare
    ];
    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    const DENY: &[u32] = &[];

    if DENY.is_empty() {
        return Ok(());
    }
    const BPF_LD: u16 = 0x00;
    const BPF_W: u16 = 0x00;
    const BPF_ABS: u16 = 0x20;
    const BPF_JMP: u16 = 0x05;
    const BPF_JEQ: u16 = 0x10;
    const BPF_K: u16 = 0x00;
    const BPF_RET: u16 = 0x06;
    const SECCOMP_RET_ERRNO: u32 = 0x0005_0000;
    const SECCOMP_RET_ALLOW: u32 = 0x7fff_0000;

    fn stmt(code: u16, k: u32) -> libc::sock_filter {
        libc::sock_filter {
            code,
            jt: 0,
            jf: 0,
            k,
        }
    }
    fn jump(code: u16, k: u32, jt: u8, jf: u8) -> libc::sock_filter {
        libc::sock_filter { code, jt, jf, k }
    }

    let mut filter = Vec::with_capacity(DENY.len() * 2 + 2);
    // seccomp_data.nr is at offset 0.
    filter.push(stmt(BPF_LD | BPF_W | BPF_ABS, 0));
    for nr in DENY {
        filter.push(jump(BPF_JMP | BPF_JEQ | BPF_K, *nr, 0, 1));
        filter.push(stmt(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | libc::EPERM as u32,
        ));
    }
    filter.push(stmt(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));

    let mut program = libc::sock_fprog {
        len: filter.len() as u16,
        filter: filter.as_mut_ptr(),
    };
    let rc = unsafe {
        libc::prctl(
            libc::PR_SET_SECCOMP,
            libc::SECCOMP_MODE_FILTER,
            &mut program as *mut libc::sock_fprog,
            0,
            0,
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().to_string())
    }
}

fn set(warnings: &mut Vec<String>, resource: libc::c_int, cur: u64, max: u64, name: &str) {
    let limit = libc::rlimit {
        rlim_cur: cur as libc::rlim_t,
        rlim_max: max as libc::rlim_t,
    };
    let rc = unsafe { libc::setrlimit(resource as _, &limit) };
    if rc != 0 {
        warnings.push(format!("{name}: {}", std::io::Error::last_os_error()));
    }
}
