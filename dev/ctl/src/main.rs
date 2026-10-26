//! veil-ctl: drives a phone running the Veil dev agent (devd).
//!
//! Every operation is a typed method of the dev API (see `veil-ctl methods`);
//! there is no shell. The connection is TLS 1.3 with this laptop's client
//! certificate, and devd's certificate is pinned at pairing.

mod conn;
mod store;

use std::process::ExitCode;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{Map, Value, json};

use conn::{CallError, Conn, pairing_code, sha256_hex};
use store::{Hosts, Identity};

const USAGE: &str = "\
usage: veil-ctl [-a HOST:PORT] COMMAND [ARGS]

  pair [--name NAME] [--scopes a,b,..] [--repin]
                          pair with devd (approve on the phone; compare the code)
  hello                   who is there, and is this laptop paired
  status                  devd, packages, and the Veil app at a glance
  methods [--all]         the methods this pairing may call (--all: every method)
  call METHOD [k=v | k:=JSON]...
                          call any method; prints the result as JSON
  logs [app|engine|devd] [-f] [--level L] [--grep TEXT]
                          read a log ring (default app); -f follows it
  screen ID               print a session's screen
  update APK...           upload, verify and install builds (Veil and/or devd)
  scenario NAME [k=v]...  run a built-in scenario (see: call scenario.list)
  forget                  drop the pinned devd certificate for the address

The address comes from -a, else $VEIL_ADDR, else the last one paired with.
Scopes: read, read.content, operate, secrets, lifecycle, scenario, update.";

fn main() -> ExitCode {
    match run(std::env::args().skip(1).collect()) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("veil-ctl: {e}");
            ExitCode::FAILURE
        }
    }
}

struct Ctx {
    addr: String,
    hosts: Hosts,
}

impl Ctx {
    fn identity(&self) -> Result<Identity, String> {
        store::identity(&client_name())
    }

    fn connect(&self) -> Result<Conn, String> {
        let pin = self.hosts.pin(&self.addr).ok_or_else(|| {
            format!(
                "not paired with {}; run: veil-ctl -a {} pair",
                self.addr, self.addr
            )
        })?;
        Conn::open(&self.addr, &self.identity()?, Some(pin)).map_err(|e| e.to_string())
    }
}

fn client_name() -> String {
    let host = std::fs::read_to_string("/proc/sys/kernel/hostname")
        .map(|s| s.trim().to_string())
        .ok()
        .filter(|h| !h.is_empty())
        .unwrap_or_else(|| "laptop".into());
    let user = std::env::var("USER").unwrap_or_else(|_| "user".into());
    format!("veil-ctl {user}@{host}")
}

fn run(mut args: Vec<String>) -> Result<ExitCode, String> {
    let mut addr = std::env::var("VEIL_ADDR").ok();
    if args.first().map(String::as_str) == Some("-a") {
        if args.len() < 2 {
            return Err("-a needs HOST:PORT".into());
        }
        addr = Some(args.remove(1));
        args.remove(0);
    }
    let Some(cmd) = args.first().cloned() else {
        println!("{USAGE}");
        return Ok(ExitCode::from(2));
    };
    let rest = args[1..].to_vec();
    if cmd == "help" || cmd == "-h" || cmd == "--help" {
        println!("{USAGE}");
        return Ok(ExitCode::SUCCESS);
    }
    let hosts = Hosts::load()?;
    let addr = addr
        .or_else(|| hosts.default.clone())
        .ok_or("no address: pass -a HOST:PORT or set VEIL_ADDR")?;
    let addr = if addr.contains(':') {
        addr
    } else {
        format!("{addr}:7390")
    };
    let mut ctx = Ctx { addr, hosts };

    match cmd.as_str() {
        "pair" => pair(&mut ctx, &rest),
        "hello" => {
            let pin = ctx.hosts.pin(&ctx.addr);
            let mut c = Conn::open(&ctx.addr, &ctx.identity()?, pin).map_err(|e| e.to_string())?;
            print_json(&c.call("devd.hello", json!({})).map_err(|e| e.to_string())?);
            Ok(ExitCode::SUCCESS)
        }
        "status" => status(&ctx),
        "methods" => methods(&ctx, rest.iter().any(|a| a == "--all")),
        "call" => {
            let method = rest.first().ok_or("call needs a METHOD")?;
            let params = parse_params(&rest[1..])?;
            let mut c = ctx.connect()?;
            match c.call(method, Value::Object(params)) {
                Ok(v) => {
                    print_json(&v);
                    Ok(ExitCode::SUCCESS)
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    Ok(ExitCode::FAILURE)
                }
            }
        }
        "logs" => logs(&ctx, &rest),
        "screen" => {
            let id = rest.first().ok_or("screen needs a session ID")?;
            let mut c = ctx.connect()?;
            let s = c
                .call("sessions.screen", json!({ "id": id }))
                .map_err(|e| e.to_string())?;
            print_screen(&s);
            Ok(ExitCode::SUCCESS)
        }
        "update" => update(&ctx, &rest),
        "scenario" => scenario(&ctx, &rest),
        "forget" => {
            ctx.hosts.pins.remove(&ctx.addr);
            if ctx.hosts.default.as_deref() == Some(&ctx.addr) {
                ctx.hosts.default = None;
            }
            ctx.hosts.save()?;
            println!(
                "forgot {} (the phone still lists the pairing until you revoke it there)",
                ctx.addr
            );
            Ok(ExitCode::SUCCESS)
        }
        other => Err(format!("unknown command {other}\n\n{USAGE}")),
    }
}

/// `k=v` is a string (devd converts it to the declared type); `k:=JSON` is
/// raw JSON (lists, numbers, booleans).
fn parse_params(args: &[String]) -> Result<Map<String, Value>, String> {
    let mut m = Map::new();
    for a in args {
        if let Some((k, v)) = a.split_once(":=") {
            let v: Value = serde_json::from_str(v).map_err(|e| format!("{k}: bad JSON: {e}"))?;
            m.insert(k.to_string(), v);
        } else if let Some((k, v)) = a.split_once('=') {
            m.insert(k.to_string(), Value::String(v.to_string()));
        } else {
            return Err(format!("argument {a:?} is not k=v or k:=JSON"));
        }
    }
    Ok(m)
}

fn print_json(v: &Value) {
    println!("{}", serde_json::to_string_pretty(v).unwrap_or_default());
}

fn pair(ctx: &mut Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut name = client_name();
    let mut scopes: Vec<String> = [
        "read",
        "read.content",
        "operate",
        "secrets",
        "lifecycle",
        "scenario",
        "update",
    ]
    .map(String::from)
    .to_vec();
    let mut repin = false;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--name" => {
                name = args.get(i + 1).ok_or("--name needs a value")?.clone();
                i += 1;
            }
            "--scopes" => {
                scopes = args
                    .get(i + 1)
                    .ok_or("--scopes needs a value")?
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .collect();
                i += 1;
            }
            "--repin" => repin = true,
            a => return Err(format!("pair: unknown argument {a}")),
        }
        i += 1;
    }
    let id = ctx.identity()?;
    let pin = if repin {
        None
    } else {
        ctx.hosts.pin(&ctx.addr)
    };
    let mut c = Conn::open(&ctx.addr, &id, pin).map_err(|e| e.to_string())?;
    let code = pairing_code(&c.server_cert, &id.cert_der);
    let server = sha256_hex(&c.server_cert);
    println!("devd at {} has certificate {}", ctx.addr, &server[..32]);
    println!();
    println!("    pairing code: {code}");
    println!();
    println!("Approve on the phone only if devd shows the same code. Waiting (2 minutes)...");
    let r = c
        .call("pair.request", json!({ "name": name, "scopes": scopes }))
        .map_err(|e| e.to_string())?;
    ctx.hosts.pins.insert(
        ctx.addr.clone(),
        json!({ "serverCertSha256": server, "name": r["name"], "pairedMs": now_ms() }),
    );
    ctx.hosts.default = Some(ctx.addr.clone());
    ctx.hosts.save()?;
    println!(
        "paired as {} with scopes {} until {}",
        r["name"],
        r["scopes"],
        fmt_time(r["expiresMs"].as_u64().unwrap_or(0))
    );
    Ok(ExitCode::SUCCESS)
}

fn status(ctx: &Ctx) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    let info = c.call("devd.info", json!({})).map_err(|e| e.to_string())?;
    let pkgs = c
        .call("packages.list", json!({}))
        .map_err(|e| e.to_string())?;
    println!(
        "devd {} at {} · listening on {} · off at {}",
        info["version"].as_str().unwrap_or("?"),
        ctx.addr,
        info["listener"]["addresses"],
        info["listener"]["offAtMs"]
            .as_u64()
            .map(fmt_time)
            .unwrap_or_else(|| "-".into())
    );
    println!(
        "this client: {} · scopes {} · until {}",
        info["caller"]["name"],
        info["caller"]["scopes"],
        info["caller"]["expiresMs"]
            .as_u64()
            .map(fmt_time)
            .unwrap_or_default()
    );
    for p in pkgs.as_array().into_iter().flatten() {
        if p["installed"].as_bool() == Some(true) {
            println!(
                "{} {} ({}) · pinned signer: {} · installer: {} · updated {}",
                p["package"].as_str().unwrap_or("?"),
                p["versionName"].as_str().unwrap_or("?"),
                p["versionCode"],
                p["pinnedSigner"],
                p["installer"].as_str().unwrap_or("-"),
                fmt_time(p["lastUpdateMs"].as_u64().unwrap_or(0))
            );
        } else {
            println!("{}: not installed", p["package"].as_str().unwrap_or("?"));
        }
    }
    if info["canInstall"].as_bool() != Some(true) {
        println!("note: devd may not install apps yet (allow it on the phone)");
    }
    match c.call("app.info", json!({})) {
        Ok(a) => println!(
            "veil: pid {} · up {}s · engine {} {} · sessions {} live / {} · pss {} KiB · fds {}",
            a["pid"],
            a["uptimeMs"].as_u64().unwrap_or(0) / 1000,
            a["engine"]["backend"].as_str().unwrap_or("?"),
            if a["engine"]["connected"].as_bool() == Some(true) {
                a["engine"]["engineVersion"].as_str().unwrap_or("?")
            } else {
                "(not connected)"
            },
            a["liveSessions"],
            a["sessions"],
            a["process"]["pssKb"],
            a["process"]["fds"]
        ),
        Err(e) => println!("veil: {e}"),
    }
    Ok(ExitCode::SUCCESS)
}

fn methods(ctx: &Ctx, all: bool) -> Result<ExitCode, String> {
    let mut c = ctx.connect()?;
    let r = c.call("methods", json!({})).map_err(|e| e.to_string())?;
    if r["veilAvailable"].as_bool() != Some(true) {
        println!("(Veil's methods are not listed: its dev build is not reachable)");
    }
    for m in r["methods"].as_array().into_iter().flatten() {
        let allowed = m["allowed"].as_bool() == Some(true);
        if !all && !allowed {
            continue;
        }
        let params: Vec<String> = m["params"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|p| {
                let mut s = format!(
                    "{}:{}",
                    p["name"].as_str().unwrap_or("?"),
                    p["type"].as_str().unwrap_or("?")
                );
                if p["required"].as_bool() == Some(true) {
                    s.push('!');
                }
                if p["secret"].as_bool() == Some(true) {
                    s.push_str("(secret)");
                }
                s
            })
            .collect();
        println!(
            "{}{:<24} {:<13} {}{}",
            if allowed { " " } else { "-" },
            m["name"].as_str().unwrap_or("?"),
            m["scope"].as_str().unwrap_or("?"),
            m["description"].as_str().unwrap_or(""),
            if params.is_empty() {
                String::new()
            } else {
                format!("\n{:40}{}", "", params.join(" "))
            }
        );
    }
    Ok(ExitCode::SUCCESS)
}

fn logs(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    let mut source = "app";
    let mut follow = false;
    let mut extra = Map::new();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "app" | "engine" | "devd" => {
                source = if args[i] == "app" {
                    "app"
                } else if args[i] == "engine" {
                    "engine"
                } else {
                    "devd"
                }
            }
            "-f" | "--follow" => follow = true,
            "--level" => {
                extra.insert(
                    "level".into(),
                    json!(args.get(i + 1).ok_or("--level needs a value")?),
                );
                i += 1;
            }
            "--grep" => {
                extra.insert(
                    "grep".into(),
                    json!(args.get(i + 1).ok_or("--grep needs a value")?),
                );
                i += 1;
            }
            a => return Err(format!("logs: unknown argument {a}")),
        }
        i += 1;
    }
    let method = format!("{source}.logs");
    let mut c = ctx.connect()?;
    let mut after = 0u64;
    loop {
        let mut params = extra.clone();
        params.insert("after".into(), json!(after));
        params.insert("max".into(), json!(1000));
        let r = c
            .call(&method, Value::Object(params))
            .map_err(|e| e.to_string())?;
        if r["dropped"].as_u64().unwrap_or(0) > 0 {
            println!("[{} entries dropped by the ring]", r["dropped"]);
        }
        for e in r["entries"].as_array().into_iter().flatten() {
            let session = e["session"].as_u64().unwrap_or(0);
            println!(
                "{} {:<5} {:<10}{} {}",
                fmt_time_ms(e["unixMs"].as_u64().unwrap_or(0)),
                e["level"].as_str().unwrap_or(""),
                e["component"].as_str().unwrap_or(""),
                if session > 0 {
                    format!(" [s{session}]")
                } else {
                    String::new()
                },
                e["message"].as_str().unwrap_or("")
            );
        }
        let next = r["nextSeq"].as_u64().unwrap_or(after);
        let got = r["entries"].as_array().map_or(0, Vec::len);
        after = next;
        if got == 1000 {
            continue;
        }
        if !follow {
            return Ok(ExitCode::SUCCESS);
        }
        std::thread::sleep(Duration::from_millis(700));
    }
}

fn print_screen(s: &Value) {
    let cols = s["cols"].as_u64().unwrap_or(80) as usize;
    let title = s["title"].as_str().unwrap_or("");
    println!(
        "┌ session {} · {}x{} · cursor {},{}{} {}",
        s["id"],
        s["cols"],
        s["rows"],
        s["cursor"]["row"],
        s["cursor"]["col"],
        if title.is_empty() {
            String::new()
        } else {
            format!(" · {title}")
        },
        "─".repeat(8)
    );
    for l in s["lines"].as_array().into_iter().flatten() {
        let l = l.as_str().unwrap_or("");
        println!("│{l:cols$}│");
    }
    println!("└{}┘", "─".repeat(cols));
}

fn update(ctx: &Ctx, apks: &[String]) -> Result<ExitCode, String> {
    use base64::Engine;
    if apks.is_empty() {
        return Err("update needs at least one APK".into());
    }
    for path in apks {
        let bytes = std::fs::read(path).map_err(|e| format!("{path}: {e}"))?;
        let sha = sha256_hex(&bytes);
        let mut c = ctx.connect()?;
        let t0 = Instant::now();
        let begin = c
            .call(
                "update.begin",
                json!({ "size": bytes.len(), "sha256": sha }),
            )
            .map_err(|e| e.to_string())?;
        let upload = begin["uploadId"].as_str().ok_or("no uploadId")?.to_string();
        // Base64 grows by 4/3: 192 KiB stays under devd's 256 KiB string limit.
        let chunk = 192 * 1024;
        let mut off = 0usize;
        while off < bytes.len() {
            let end = (off + chunk).min(bytes.len());
            c.call(
                "update.chunk",
                json!({
                    "uploadId": upload,
                    "offset": off,
                    "data": base64::engine::general_purpose::STANDARD.encode(&bytes[off..end]),
                }),
            )
            .map_err(|e| e.to_string())?;
            off = end;
        }
        eprintln!(
            "{path}: uploaded {} KiB in {:.1}s; verifying and installing",
            bytes.len() / 1024,
            t0.elapsed().as_secs_f64()
        );
        let rec = c
            .call("update.commit", json!({ "uploadId": upload }))
            .map_err(|e| format!("{path}: {e}"))?;
        let install_id = rec["installId"].as_i64().ok_or("no installId")?;
        let self_update = rec["package"].as_str() == Some("io.github.franticplant.veil.devd");
        drop(c);
        let deadline = Instant::now() + Duration::from_secs(300);
        let mut told = false;
        loop {
            std::thread::sleep(Duration::from_millis(800));
            if Instant::now() > deadline {
                return Err(format!(
                    "{path}: install {install_id} did not finish in 5 minutes"
                ));
            }
            // A devd self-update ends devd mid-way; reconnect until it is back.
            let Ok(mut c) = ctx.connect() else {
                continue;
            };
            let Ok(list) = c.call("update.status", json!({})) else {
                continue;
            };
            let Some(r) = list
                .as_array()
                .into_iter()
                .flatten()
                .find(|r| r["installId"].as_i64() == Some(install_id))
            else {
                continue;
            };
            match r["state"].as_str() {
                Some("success") => {
                    println!(
                        "{path}: installed {} {} ({}) in {:.1}s",
                        r["package"].as_str().unwrap_or("?"),
                        r["versionName"].as_str().unwrap_or("?"),
                        r["versionCode"],
                        t0.elapsed().as_secs_f64()
                    );
                    break;
                }
                Some("failure") => {
                    return Err(format!(
                        "{path}: install failed: {}",
                        r["message"].as_str().unwrap_or("")
                    ));
                }
                Some("pendingUserAction") if !told => {
                    eprintln!(
                        "{path}: Android wants a tap on the phone (first install by devd); waiting"
                    );
                    told = true;
                }
                _ if self_update && !told => {
                    eprintln!("{path}: devd is replacing itself; waiting for it to come back");
                    told = true;
                }
                _ => {}
            }
        }
    }
    Ok(ExitCode::SUCCESS)
}

fn scenario(ctx: &Ctx, args: &[String]) -> Result<ExitCode, String> {
    let name = args.first().ok_or("scenario needs a NAME")?;
    let mut params = parse_params(&args[1..])?;
    params.insert("name".into(), json!(name));
    let mut c = ctx.connect()?;
    let r = c
        .call("scenario.run", Value::Object(params))
        .map_err(|e: CallError| e.to_string())?;
    for s in r["steps"].as_array().into_iter().flatten() {
        println!(
            "  {} {:<24} {:>7} ms  {}",
            if s["ok"].as_bool() == Some(true) {
                "ok  "
            } else {
                "FAIL"
            },
            s["step"].as_str().unwrap_or("?"),
            s["ms"],
            s["detail"].as_str().unwrap_or("")
        );
    }
    let metrics = &r["metrics"];
    if metrics.as_object().is_some_and(|m| !m.is_empty()) {
        println!("  metrics: {metrics}");
    }
    let ok = r["ok"].as_bool() == Some(true);
    println!(
        "{} {} (v{}) in {} ms{}",
        if ok { "PASS" } else { "FAIL" },
        r["scenario"].as_str().unwrap_or("?"),
        r["version"],
        r["totalMs"],
        r["error"]
            .as_str()
            .map(|e| format!(": {e}"))
            .unwrap_or_default()
    );
    Ok(if ok {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    })
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// UTC "YYYY-MM-DD HH:MM", without a date library.
fn fmt_time(ms: u64) -> String {
    let (y, mo, d, h, mi, _, _) = civil(ms);
    format!("{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}Z")
}

/// UTC "HH:MM:SS.mmm".
fn fmt_time_ms(ms: u64) -> String {
    let (_, _, _, h, mi, s, milli) = civil(ms);
    format!("{h:02}:{mi:02}:{s:02}.{milli:03}")
}

fn civil(ms: u64) -> (i64, u32, u32, u32, u32, u32, u32) {
    let secs = (ms / 1000) as i64;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Howard Hinnant's days-to-civil.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (
        y,
        m,
        d,
        (rem / 3600) as u32,
        (rem % 3600 / 60) as u32,
        (rem % 60) as u32,
        (ms % 1000) as u32,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn params() {
        let m = parse_params(&[
            "id=3".into(),
            "keys:=[\"ctrl+c\",\"enter\"]".into(),
            "text=a=b".into(),
        ])
        .unwrap();
        assert_eq!(m["id"], json!("3"));
        assert_eq!(m["keys"], json!(["ctrl+c", "enter"]));
        assert_eq!(m["text"], json!("a=b"));
        assert!(parse_params(&["nope".into()]).is_err());
    }

    #[test]
    fn times() {
        assert_eq!(fmt_time(0), "1970-01-01 00:00Z");
        assert_eq!(fmt_time(1_793_000_000_000), "2026-10-26 07:33Z");
        assert_eq!(fmt_time_ms(1_234), "00:00:01.234");
    }

    #[test]
    fn pairing_code_is_six_digits_and_binds_both_certs() {
        let a = pairing_code(b"server", b"client");
        assert_eq!(a.len(), 6);
        assert_ne!(a, pairing_code(b"server", b"other"));
        assert_ne!(a, pairing_code(b"other", b"client"));
        // Shared with devd's TlsTest: both sides must compute the same code.
        assert_eq!(a, "478424");
    }
}
