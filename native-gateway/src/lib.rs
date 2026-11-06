//! JNI boundary for the bundled Node/Magpie runtime and its process supervisor.
//!
//! Design rules (kept deliberately strict, see docs/ARCHITECTURE.md):
//! * Liveness is decided by the child handle (or, for an instance inherited from a
//!   previous app process, by the pid recorded in `service.lock` **and** verified
//!   against `/proc/<pid>/cmdline`), never by "something is listening on the port".
//! * A pid that only exists as a zombie is *not* alive.
//! * Stopping always goes through the child's process group (Node + Magpie
//!   grandchildren), TERM first, KILL after a bounded wait.
//! * Anything that can panic is wrapped in `catch_unwind`: a Rust panic must never
//!   unwind across the JNI boundary (that aborts the whole app process).

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::fs;
use std::io::{Read, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
#[cfg(unix)]
use std::os::unix::process::CommandExt;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The gateway process started by *this* app process (if any).
static PROCESS: Mutex<Option<Child>> = Mutex::new(None);
/// pid of a gateway inherited from a previous app process (adopted instance).
static ADOPTED_PID: AtomicI32 = AtomicI32::new(0);

const DEFAULT_PORT: i32 = 15000;
const STOP_GRACE_MS: u64 = 3000;

fn string_arg<'a>(env: &mut JNIEnv<'a>, value: &JString<'a>) -> Result<String, jni::errors::Error> {
    Ok(env.get_string(value)?.into())
}

fn runtime_paths(files_dir: &str, native_dir: &str) -> (PathBuf, PathBuf) {
    let root = Path::new(files_dir).join("ofm-runtime");
    (root.join("app"), Path::new(native_dir).to_path_buf())
}

fn ready(files_dir: &str) -> bool {
    Path::new(files_dir).join("ofm-runtime/app/packages/standalone/cli.mjs").is_file()
}

/// From the lock JSON text, take the `pid` number (minimal parse, no JSON crate).
fn parse_lock_pid(text: &str) -> Option<i32> {
    let idx = text.find("\"pid\"")?;
    let rest = &text[idx + 5..];
    let colon = rest.find(':')?;
    let digits: String = rest[colon + 1..].trim_start().chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok()
}

/// `Z` (zombie) and `X` (dead) are not alive even though `/proc/<pid>` still exists.
fn proc_state(pid: i32) -> Option<char> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // Fields after the parenthesised command name: state is the first one.
    let end = stat.rfind(')')?;
    stat[end + 1..].trim_start().chars().next()
}

fn proc_alive(pid: i32) -> bool {
    if pid <= 0 {
        return false;
    }
    matches!(proc_state(pid), Some(s) if s != 'Z' && s != 'X')
}

/// Identity check: only adopt a pid that is really our `cli.mjs`, not any random
/// process that happens to have reused the pid recorded in a stale lock file.
fn proc_is_our_gateway(pid: i32) -> bool {
    if !proc_alive(pid) {
        return false;
    }
    match fs::read(format!("/proc/{pid}/cmdline")) {
        Ok(raw) => String::from_utf8_lossy(&raw).contains("cli.mjs"),
        Err(_) => false,
    }
}

/// Replace the one-shot management token in a log line, returning the token so the
/// caller can persist it to a 0600 file (the console can still auto-login, while the
/// log file itself stays safe to screenshot / copy / paste into a public issue).
fn scrub_login_token(line: &str) -> (String, Option<String>) {
    let Some(marker) = line.find("#login=") else { return (line.to_string(), None) };
    let start = marker + "#login=".len();
    let token: String = line[start..]
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect();
    if token.is_empty() {
        return (line.to_string(), None);
    }
    let mut out = String::with_capacity(line.len());
    out.push_str(&line[..start]);
    out.push_str("***");
    out.push_str(&line[start + token.len()..]);
    (out, Some(token))
}

/// Convert days since 1970-01-01 to a civil date (Howard Hinnant's `civil_from_days`).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    let y = if m <= 2 { y + 1 } else { y };
    (y, m as u32, d as u32)
}

fn split_local(now: i64, offset_min: i64) -> (String, String) {
    let local = now + offset_min * 60;
    let days = local.div_euclid(86400);
    let secs = local.rem_euclid(86400);
    let (y, m, d) = civil_from_days(days);
    let (hh, mm, ss) = (secs / 3600, (secs % 3600) / 60, secs % 60);
    (format!("{y:04}-{m:02}-{d:02}"), format!("{hh:02}:{mm:02}:{ss:02}"))
}

fn local_stamp(now: i64, offset_min: i64) -> String {
    let (date, time) = split_local(now, offset_min);
    format!("{date} {time}")
}

fn now_secs() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Daily-rotating log sink. The local date is recomputed on every write, so a
/// long-running gateway still rolls over at local midnight.
struct DailyLog {
    dir: PathBuf,
    offset_min: i64,
    current_date: String,
    file: Option<fs::File>,
    /// Where the current one-shot management token is stored (mode 0600).
    token_path: PathBuf,
}

impl DailyLog {
    fn new(dir: PathBuf, offset_min: i64, token_path: PathBuf) -> Self {
        let mut log = DailyLog {
            dir,
            offset_min,
            current_date: String::new(),
            file: None,
            token_path,
        };
        let header = format!("===== OFM Gateway session start {} =====\n", local_stamp(now_secs(), offset_min));
        log.write_line(header.as_bytes());
        log
    }

    fn write_line(&mut self, data: &[u8]) {
        let raw = String::from_utf8_lossy(data);
        let (line, token) = scrub_login_token(&raw);
        if let Some(token) = token {
            self.store_token(&token);
        }
        self.write_scrubbed(line.as_bytes());
    }

    /// Persist the one-shot token so the in-app console can still auto-login after the
    /// log file has been redacted. App-private directory, owner-only permissions.
    fn store_token(&self, token: &str) {
        if let Some(parent) = self.token_path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::remove_file(&self.token_path);
        if let Ok(mut file) = fs::OpenOptions::new().create_new(true).write(true).open(&self.token_path) {
            let _ = file.write_all(token.as_bytes());
            let _ = file.flush();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = fs::set_permissions(&self.token_path, fs::Permissions::from_mode(0o600));
            }
        }
    }

    fn write_scrubbed(&mut self, data: &[u8]) {
        let (date, _) = split_local(now_secs(), self.offset_min);
        if self.current_date != date || self.file.is_none() {
            let _ = fs::create_dir_all(&self.dir);
            let path = self.dir.join(format!("gateway-{date}.log"));
            self.file = fs::OpenOptions::new().create(true).append(true).open(path).ok();
            self.current_date = date;
        }
        if let Some(file) = self.file.as_mut() {
            let _ = file.write_all(data);
            let _ = file.flush();
        }
    }
}

/// Drains a child pipe into the log. Lines are buffered so the token redaction always
/// sees a whole token, even when a pipe read splits the URL in two.
fn spawn_reader<R: Read + Send + 'static>(mut reader: R, logger: Arc<Mutex<DailyLog>>) {
    std::thread::spawn(move || {
        let mut buf = [0u8; 8192];
        let mut pending: Vec<u8> = Vec::new();
        loop {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    pending.extend_from_slice(&buf[..n]);
                    let mut start = 0usize;
                    for (idx, byte) in pending.iter().enumerate() {
                        if *byte == b'\n' {
                            if let Ok(mut guard) = logger.lock() {
                                guard.write_line(&pending[start..=idx]);
                            }
                            start = idx + 1;
                        }
                    }
                    pending.drain(..start);
                    // Defensive cap: a single unterminated line never grows unbounded.
                    if pending.len() > 256 * 1024 {
                        if let Ok(mut guard) = logger.lock() {
                            guard.write_line(&pending);
                        }
                        pending.clear();
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
        if !pending.is_empty() {
            if let Ok(mut guard) = logger.lock() {
                guard.write_line(&pending);
            }
        }
    });
}

fn logs_dir(files_dir: &str) -> PathBuf {
    Path::new(files_dir).join("ofm-runtime/logs")
}

fn data_dir(files_dir: &str) -> PathBuf {
    Path::new(files_dir).join("ofm-data")
}

fn token_path(files_dir: &str) -> PathBuf {
    Path::new(files_dir).join("ofm-runtime/bootstrap.token")
}

fn logger_for(files_dir: &str, tz_offset_min: i64) -> DailyLog {
    DailyLog::new(logs_dir(files_dir), tz_offset_min, token_path(files_dir))
}

fn child_running(slot: &mut Option<Child>) -> bool {
    match slot.as_mut() {
        Some(child) => child.try_wait().ok().flatten().is_none(),
        None => false,
    }
}

fn adopted_running() -> bool {
    let pid = ADOPTED_PID.load(Ordering::SeqCst);
    if pid <= 0 {
        return false;
    }
    if proc_alive(pid) {
        true
    } else {
        ADOPTED_PID.store(0, Ordering::SeqCst);
        false
    }
}

/// Kill a pid's whole process group: TERM first, then KILL after the grace period.
fn terminate_group(pid: i32) -> bool {
    if pid <= 0 {
        return true;
    }
    let _ = Command::new("/system/bin/sh")
        .arg("-c")
        .arg(format!("kill -TERM -- -{pid} 2>/dev/null || kill -TERM {pid} 2>/dev/null"))
        .status();
    let mut waited = 0u64;
    while waited < STOP_GRACE_MS {
        if !proc_alive(pid) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
        waited += 100;
    }
    let _ = Command::new("/system/bin/sh")
        .arg("-c")
        .arg(format!("kill -KILL -- -{pid} 2>/dev/null || kill -KILL {pid} 2>/dev/null"))
        .status();
    waited = 0;
    while waited < STOP_GRACE_MS {
        if !proc_alive(pid) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
        waited += 100;
    }
    !proc_alive(pid)
}

fn status_key(files_dir: &str, native_dir: &str) -> &'static str {
    let (app, libs) = runtime_paths(files_dir, native_dir);
    if !app.join("packages/standalone/cli.mjs").is_file() {
        "runtime_missing"
    } else if !libs.join("libnode.so").is_file() {
        "node_missing"
    } else if !libs.join("libmagpie.so").is_file() {
        "magpie_missing"
    } else if PROCESS.lock().map(|mut p| child_running(&mut p)).unwrap_or(false) {
        "running"
    } else if adopted_running() {
        "running"
    } else {
        "ready"
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeRuntimeAvailable<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    native_dir: JString<'local>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let (Ok(files), Ok(native)) = (string_arg(&mut env, &files_dir), string_arg(&mut env, &native_dir)) else {
            return JNI_FALSE;
        };
        let (app, libs) = runtime_paths(&files, &native);
        if app.join("packages/standalone/cli.mjs").is_file()
            && libs.join("libnode.so").is_file()
            && libs.join("libmagpie.so").is_file()
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// Returns a stable ASCII key, never localized text. The UI owns presentation.
#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeStatus<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    native_dir: JString<'local>,
) -> jstring {
    let status = catch_unwind(AssertUnwindSafe(|| {
        match (string_arg(&mut env, &files_dir), string_arg(&mut env, &native_dir)) {
            (Ok(files), Ok(native)) => status_key(&files, &native),
            _ => "invalid_path",
        }
    }))
    .unwrap_or("internal_error");
    env.new_string(status).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    native_dir: JString<'local>,
    tz_offset_min: jint,
    port: jint,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let (Ok(files), Ok(native)) = (string_arg(&mut env, &files_dir), string_arg(&mut env, &native_dir)) else {
            return JNI_FALSE;
        };
        if !ready(&files) {
            return JNI_FALSE;
        }
        let (app, libs) = runtime_paths(&files, &native);
        let port = if (1..=65535).contains(&port) { port } else { DEFAULT_PORT };
        let data = data_dir(&files);
        let tmp_dir = Path::new(&files).join("ofm-runtime/tmp");
        let _ = fs::create_dir_all(&data);
        let _ = fs::create_dir_all(&tmp_dir);

        let mut slot = match PROCESS.lock() {
            Ok(lock) => lock,
            Err(_) => return JNI_FALSE,
        };
        if child_running(&mut slot) {
            return JNI_TRUE;
        }
        // A gateway left over from a previous app process: adopt it instead of
        // starting a second one, so status/stop keep telling the truth.
        if adopted_running() {
            return JNI_TRUE;
        }
        let lock_path = data.join("service.lock");
        if let Ok(text) = fs::read_to_string(&lock_path) {
            if let Some(pid) = parse_lock_pid(&text) {
                if proc_is_our_gateway(pid) {
                    ADOPTED_PID.store(pid, Ordering::SeqCst);
                    let mut logger = logger_for(&files, tz_offset_min as i64);
                    logger.write_line(format!("adopted existing gateway pid {pid}\n").as_bytes());
                    return JNI_TRUE;
                }
                let mut logger = logger_for(&files, tz_offset_min as i64);
                logger.write_line(format!("stale service.lock pid {pid} (not alive); removing\n").as_bytes());
            }
            let _ = fs::remove_file(&lock_path);
        }

        let node = libs.join("libnode.so");
        let child = Command::new(node)
            .current_dir(&app)
            .arg("packages/standalone/cli.mjs")
            .arg("--data-dir")
            .arg(&data)
            .arg("--port")
            .arg(port.to_string())
            .arg("--no-lan")
            // Explicit whitelist: never inherit the (possibly huge / stale) app env.
            .env_clear()
            .env("PATH", "/system/bin:/system/xbin")
            .env("LD_LIBRARY_PATH", &native)
            .env("OFM_MAGPIE_BINARY", libs.join("libmagpie.so"))
            // Magpie 装 @magpie-community 插件要 fork/exec bun。它自己下载到
            // files/…/cache/magpie/bun/<ver>/magpie-bun 会 EACCES：Android 10+ 只允许从
            // nativeLibraryDir 这类只读目录 exec。bun 因此随包以 libbun.so 分发，
            // 这里把路径交给 JS 层，由 magpie-engine.js 注入成 MAGPIE_BUN。
            // 前缀必须是 OFM_：引擎启动前会清掉所有继承的 MAGPIE_* 键。
            .env("OFM_MAGPIE_BUN", libs.join("libbun.so"))
            .env("OFM_HOME", &data)
            .env("OFM_MANAGED_ROOT", &files)
            .env("HOME", &data)
            .env("TMPDIR", &tmp_dir)
            .env("XDG_CACHE_HOME", &tmp_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .process_group(0) // own process group, so Magpie grandchildren die with us
            .spawn();
        match child {
            Ok(mut child) => {
                let pid = child.id() as i32;
                let logger = Arc::new(Mutex::new(logger_for(&files, tz_offset_min as i64)));
                if let Some(out) = child.stdout.take() {
                    spawn_reader(out, logger.clone());
                }
                if let Some(err) = child.stderr.take() {
                    spawn_reader(err, logger.clone());
                }
                // Supervisor: reports the exit. Restarting is the service's job (it knows
                // whether the user asked for the gateway to be up).
                {
                    let logger = logger.clone();
                    std::thread::spawn(move || {
                        loop {
                            std::thread::sleep(Duration::from_secs(2));
                            if !proc_alive(pid) {
                                if let Ok(mut guard) = logger.lock() {
                                    guard.write_line(format!("gateway process {pid} exited\n").as_bytes());
                                }
                                break;
                            }
                        }
                    });
                }
                ADOPTED_PID.store(0, Ordering::SeqCst);
                *slot = Some(child);
                JNI_TRUE
            }
            Err(err) => {
                let mut logger = logger_for(&files, tz_offset_min as i64);
                logger.write_line(format!("spawn error: {err}\n").as_bytes());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE);
    result
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeStop<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    _native_dir: JString<'local>,
) -> jboolean {
    let files = string_arg(&mut env, &files_dir).ok();
    let stopped = catch_unwind(AssertUnwindSafe(|| {
        // Take the child out of the slot first so status polling never blocks on stop.
        let taken = match PROCESS.lock() {
            Ok(mut slot) => slot.take(),
            Err(_) => None,
        };
        let mut handled = false;
        if let Some(mut child) = taken {
            handled = true;
            let pid = child.id() as i32;
            let _ = terminate_group(pid);
            let _ = child.wait();
        }
        let adopted = ADOPTED_PID.swap(0, Ordering::SeqCst);
        if adopted > 0 {
            handled = true;
            terminate_group(adopted);
        }
        // Only an instance we actually terminated may clear the lock, otherwise a
        // concurrent second instance would be unlocked.
        if handled {
            if let Some(files) = files.as_ref() {
                let _ = fs::remove_file(data_dir(files).join("service.lock"));
            }
        }
        if let Some(files) = files.as_ref() {
            let mut logger = logger_for(files, 0);
            logger.write_line(if handled { b"gateway stopped\n" } else { b"stop requested; nothing running\n" });
        }
        handled
    }))
    .unwrap_or(false);
    if stopped { JNI_TRUE } else { JNI_FALSE }
}

/// pid of the running gateway (own child or adopted instance); -1 when idle.
#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativePid(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut slot) = PROCESS.lock() {
            if let Some(child) = slot.as_mut() {
                if child.try_wait().ok().flatten().is_none() {
                    return child.id() as jint;
                }
            }
        }
        let adopted = ADOPTED_PID.load(Ordering::SeqCst);
        if adopted > 0 && proc_alive(adopted) {
            adopted
        } else {
            -1
        }
    }))
    .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pid_from_lock_json() {
        assert_eq!(parse_lock_pid(r#"{"pid":1234,"product":"standalone"}"#), Some(1234));
        assert_eq!(parse_lock_pid(r#"{ "pid" : 42 }"#), Some(42));
        assert_eq!(parse_lock_pid("no pid here"), None);
        assert_eq!(parse_lock_pid(r#"{"pid":"abc"}"#), None);
    }

    #[test]
    fn redacts_login_token_and_keeps_the_rest() {
        let line = "管理页面（10 分钟内一次性登录）：http://127.0.0.1:15000/#login=abcDEF123-_x\n";
        let (out, token) = scrub_login_token(line);
        assert_eq!(token.as_deref(), Some("abcDEF123-_x"));
        assert!(out.contains("#login=***"));
        assert!(!out.contains("abcDEF123"));
        assert!(out.contains("管理页面"));
        assert!(out.ends_with('\n'));
    }

    #[test]
    fn leaves_other_lines_untouched() {
        let line = "started on http://127.0.0.1:15000/v1\n";
        assert_eq!(scrub_login_token(line).0, line);
        assert_eq!(scrub_login_token("").0, "");
        assert_eq!(scrub_login_token("#login=").1, None);
    }

    #[test]
    fn civil_dates_round_trip_known_values() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_000), (2022, 1, 8));
        assert_eq!(civil_from_days(-1), (1969, 12, 31));
        assert_eq!(civil_from_days(20_000), (2024, 10, 4));
    }

    #[test]
    fn local_midnight_rolls_over() {
        let (date, time) = split_local(1_700_000_000, 480);
        assert_eq!(date, "2023-11-15");
        assert_eq!(
            split_local(86_399, 0),
            ("1970-01-01".to_string(), "23:59:59".to_string())
        );
        assert_eq!(
            split_local(86_400, 0),
            ("1970-01-02".to_string(), "00:00:00".to_string())
        );
        assert!(time.starts_with("0") || time.starts_with("1") || time.starts_with("2"));
    }

    #[test]
    fn zombieish_pids_are_never_alive() {
        assert!(!proc_alive(0));
        assert!(!proc_alive(-1));
        assert!(!proc_alive(i32::MAX));
    }
}
