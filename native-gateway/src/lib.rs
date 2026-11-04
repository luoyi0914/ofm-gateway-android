use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
#[cfg(unix)]
use std::os::unix::process::CommandExt;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

static PROCESS: Mutex<Option<Child>> = Mutex::new(None);

fn string_arg<'a>(env: &mut JNIEnv<'a>, value: &JString<'a>) -> Result<String, jni::errors::Error> {
    Ok(env.get_string(value)?.into())
}

fn runtime_paths(files_dir: &str, native_dir: &str) -> (PathBuf, PathBuf) {
    let root = Path::new(files_dir).join("ofm-runtime");
    (root.join("app"), Path::new(native_dir).to_path_buf())
}

fn ready(files_dir: &str) -> bool {
    let app = Path::new(files_dir).join("ofm-runtime/app/packages/standalone/cli.mjs");
    app.is_file()
}

/// 从 service.lock 的 JSON 文本里取 pid（最小解析，不引入 JSON 依赖）。
fn parse_lock_pid(text: &str) -> Option<i32> {
    let idx = text.find("\"pid\"")?;
    let rest = &text[idx + 5..];
    let colon = rest.find(':')?;
    let digits: String = rest[colon + 1..].trim_start().chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok()
}

/// Convert days since 1970-01-01 to a civil (year, month, day) in the proleptic
/// Gregorian calendar. Howard Hinnant's `civil_from_days` algorithm; no external
/// crate is needed so the build stays fully offline-capable.
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

/// A daily-rotating log sink. Every write recomputes the local date from the
/// current instant plus the device UTC offset supplied by Kotlin, so a single
/// long-running gateway process still rolls over at local midnight.
struct DailyLog {
    dir: PathBuf,
    offset_min: i64,
    current_date: String,
    file: Option<fs::File>,
}

impl DailyLog {
    fn new(dir: PathBuf, offset_min: i64) -> Self {
        let mut log = DailyLog { dir, offset_min, current_date: String::new(), file: None };
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        let header = format!("===== OFM Gateway session start {} =====\n", local_stamp(now, offset_min));
        log.write_bytes(header.as_bytes());
        log
    }

    fn write_bytes(&mut self, data: &[u8]) {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        let (date, _) = split_local(now, self.offset_min);
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

fn spawn_reader<R: Read + Send + 'static>(mut reader: R, logger: Arc<Mutex<DailyLog>>) {
    // Keep draining the pipe even if writing the file fails, otherwise a full
    // pipe buffer (64 KB) would block the Node child and stall the gateway.
    std::thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if let Ok(mut guard) = logger.lock() {
                        guard.write_bytes(&buf[..n]);
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeRuntimeAvailable<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, files_dir: JString<'local>, native_dir: JString<'local>,
) -> jboolean {
    let (Ok(files), Ok(native)) = (string_arg(&mut env, &files_dir), string_arg(&mut env, &native_dir)) else { return JNI_FALSE };
    let (app, libs) = runtime_paths(&files, &native);
    if app.join("packages/standalone/cli.mjs").is_file()
        && libs.join("libnode.so").is_file()
        && libs.join("libmagpie.so").is_file() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeStatus<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, files_dir: JString<'local>, native_dir: JString<'local>,
) -> jstring {
    let status = match (string_arg(&mut env, &files_dir), string_arg(&mut env, &native_dir)) {
        (Ok(files), Ok(native)) => {
            let (app, libs) = runtime_paths(&files, &native);
            if !app.join("packages/standalone/cli.mjs").is_file() { "runtime app not installed" }
            else if !libs.join("libnode.so").is_file() { "Node library missing" }
            else if !libs.join("libmagpie.so").is_file() { "Magpie library missing" }
            else if PROCESS.lock().ok().and_then(|mut p| p.as_mut().map(|c| c.try_wait().ok().flatten().is_none())).unwrap_or(false) { "gateway running" }
            else { "runtime ready" }
        }
        _ => "invalid runtime path",
    };
    env.new_string(status).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeStart<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, files_dir: JString<'local>, native_dir: JString<'local>,
    tz_offset_min: jint,
) -> jboolean {
    let (Ok(files), Ok(native)) = (string_arg(&mut env, &files_dir), string_arg(&mut env, &native_dir)) else { return JNI_FALSE };
    if !ready(&files) { return JNI_FALSE; }
    let (app, libs) = runtime_paths(&files, &native);
    let mut slot = match PROCESS.lock() { Ok(lock) => lock, Err(_) => return JNI_FALSE };
    if let Some(child) = slot.as_mut() {
        if child.try_wait().ok().flatten().is_none() { return JNI_TRUE; }
    }
    let logs_dir = Path::new(&files).join("ofm-runtime/logs");
    let data_dir = Path::new(&files).join("ofm-data");
    let tmp_dir = Path::new(&files).join("ofm-runtime/tmp");
    let _ = fs::create_dir_all(&data_dir);
    let _ = fs::create_dir_all(&tmp_dir);
    let _ = fs::create_dir_all(&logs_dir);
    // 锁文件记录了持有者 pid。只有「pid 仍存活」才说明确实有实例在跑（例如上次 app 进程被杀后
    // 遗留的 Node 孤儿），此时跳过拉起；否则按陈旧锁清除后再启动。
    // 不能用「15000 端口有监听」直接判定属于我们：那可能是其他应用占用，会误报已启动。
    let lock_path = data_dir.join("service.lock");
    if let Ok(text) = fs::read_to_string(&lock_path) {
        if let Some(pid) = parse_lock_pid(&text) {
            if pid > 0 && Path::new(&format!("/proc/{pid}")).exists() {
                let mut logger = DailyLog::new(logs_dir.clone(), tz_offset_min as i64);
                logger.write_bytes(format!("existing gateway pid {pid} alive; skip spawn\n").as_bytes());
                return JNI_TRUE;
            }
        }
        let _ = fs::remove_file(&lock_path);
    }
    let node = libs.join("libnode.so");
    let child = Command::new(node)
        .current_dir(&app)
        .arg("packages/standalone/cli.mjs")
        .arg("--data-dir").arg(&data_dir)
        .arg("--port").arg("15000")
        .arg("--no-lan")
        .env("LD_LIBRARY_PATH", &native)
        .env("OFM_MAGPIE_BINARY", libs.join("libmagpie.so"))
        .env("OFM_HOME", &data_dir)
        .env("OFM_MANAGED_ROOT", &files)
        .env("HOME", &data_dir)
        .env("TMPDIR", &tmp_dir)
        .env("XDG_CACHE_HOME", &tmp_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0)
        .spawn();
    match child {
        Ok(mut child) => {
            let pid = child.id();
            let logger = Arc::new(Mutex::new(DailyLog::new(logs_dir, tz_offset_min as i64)));
            if let Some(out) = child.stdout.take() { spawn_reader(out, logger.clone()); }
            if let Some(err) = child.stderr.take() { spawn_reader(err, logger.clone()); }
            // 监督线程：子进程退出（崩溃/OOM/端口冲突）时在日志里明确记录，避免“服务在、网关静默挂掉”。
            {
                let logger = logger.clone();
                std::thread::spawn(move || loop {
                    std::thread::sleep(std::time::Duration::from_secs(2));
                    if !Path::new(&format!("/proc/{pid}")).exists() {
                        if let Ok(mut guard) = logger.lock() {
                            guard.write_bytes(format!("gateway process {pid} exited\n").as_bytes());
                        }
                        break;
                    }
                });
            }
            *slot = Some(child);
            JNI_TRUE
        }
        Err(err) => {
            let mut logger = DailyLog::new(logs_dir, tz_offset_min as i64);
            logger.write_bytes(format!("spawn error: {err}\n").as_bytes());
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativeStop<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, files_dir: JString<'local>, _native_dir: JString<'local>,
) -> jboolean {
    let files = string_arg(&mut env, &files_dir);
    // 先从槽里取出子进程并释放 PROCESS 锁，避免阻塞状态轮询。
    let taken = match PROCESS.lock() { Ok(mut slot) => slot.take(), Err(_) => None };
    let mut had_child = false;
    if let Some(mut child) = taken {
        had_child = true;
        let pid = child.id();
        // 子进程用独立进程组启动（process_group(0)），先优雅 TERM 整个进程组（含 Magpie 孙进程），
        // 让 Node 有机会执行 service.close()/magpieEngine.close() 释放锁；超时再 SIGKILL。
        let _ = Command::new("/system/bin/sh")
            .arg("-c")
            .arg(format!("kill -TERM -- -{pid} 2>/dev/null || kill -TERM {pid} 2>/dev/null"))
            .status();
        for _ in 0..30 {
            if let Ok(Some(_)) = child.try_wait() { break; }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        let _ = child.kill();
        let _ = child.wait();
    }
    // 仅在确实终止了我们自己的子进程时清理锁；否则不要误删其他实例的锁。
    if had_child {
        if let Ok(files) = files {
            let _ = fs::remove_file(Path::new(&files).join("ofm-data/service.lock"));
        }
    }
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_cn_luoyi0914_ofmgateway_NativeGateway_nativePid(
    _env: JNIEnv, _class: JClass,
) -> jint {
    match PROCESS.lock() {
        Ok(mut slot) => match slot.as_mut() {
            Some(child) => match child.try_wait() {
                Ok(None) => child.id() as jint,
                _ => -1,
            },
            None => -1,
        },
        Err(_) => -1,
    }
}
