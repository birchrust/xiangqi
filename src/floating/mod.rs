#[cfg(target_os = "android")]
mod android;
mod ui;

pub use ui::{SettingsButton, StartButton};

fn settings() -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        android::settings()
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("引擎设置仅支持 Android，请在模拟器或真机运行。".into())
    }
}

fn show() -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        android::show()
    }

    #[cfg(not(target_os = "android"))]
    {
        Err("系统悬浮窗仅支持 Android，请在模拟器或真机运行。".into())
    }
}

fn close() -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        android::close()
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(())
    }
}

fn is_open() -> Result<bool, String> {
    #[cfg(target_os = "android")]
    {
        android::is_open()
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(false)
    }
}
