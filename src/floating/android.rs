use dioxus::prelude::manganis;

// Bundle Kotlin, recognition models, and the engine.
#[manganis::ffi("android/assistant")]
unsafe extern "Kotlin" {
    type XiangqiAssistant;
}

pub fn show() -> Result<(), String> {
    action("showFloatingWindow")
}

pub fn close() -> Result<(), String> {
    action("closeFloatingWindow")
}

pub fn settings() -> Result<(), String> {
    action("showEngineSettings")
}

fn action(method: &str) -> Result<(), String> {
    manganis::android::with_activity(|env, activity| {
        let result = env.call_method(activity, method, "()V", &[]);
        Some(match result {
            Ok(_) => Ok(()),
            Err(error) => {
                // Clear JNI exceptions before returning to Dioxus.
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_describe();
                    let _ = env.exception_clear();
                }
                Err(format!("悬浮窗操作失败：{error}"))
            }
        })
    })
    .unwrap_or_else(|| Err("Android 界面尚未就绪，请稍后重试。".into()))
}

pub fn is_open() -> Result<bool, String> {
    manganis::android::with_activity(|env, activity| {
        let result = env
            .call_method(activity, "isFloatingWindowOpen", "()Z", &[])
            .and_then(|value| value.z());
        Some(match result {
            Ok(open) => Ok(open),
            Err(error) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_describe();
                    let _ = env.exception_clear();
                }
                Err(format!("无法读取悬浮窗状态：{error}"))
            }
        })
    })
    .unwrap_or_else(|| Err("Android 界面尚未就绪，请稍后重试。".into()))
}
