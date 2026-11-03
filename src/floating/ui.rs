use dioxus::prelude::*;

#[component]
pub fn SettingsButton() -> Element {
    let mut error = use_signal(|| None::<String>);
    rsx! {
        button {
            class: "settings-button",
            r#type: "button",
            onclick: move |_| error.set(super::settings().err()),
            "设置"
        }
        if let Some(message) = error() {
            p { class: "floating-error", role: "alert", "{message}" }
        }
    }
}

#[component]
pub fn StartButton() -> Element {
    let mut error = use_signal(|| None::<String>);
    #[allow(unused_mut)]
    let mut opened = use_signal(|| false);

    #[cfg(target_os = "android")]
    use_future(move || async move {
        loop {
            if let Ok(actual) = super::is_open() {
                if *opened.peek() != actual {
                    opened.set(actual);
                }
            }
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        }
    });

    rsx! {
        button {
            r#type: "button",
            onclick: move |_| {
                let actual = super::is_open().unwrap_or(opened());
                let result = if actual { super::close() } else { super::show() };
                error.set(result.err());
            },
            if opened() { "关闭" } else { "启动" }
        }
        if let Some(message) = error() {
            p { class: "floating-error", role: "alert", "{message}" }
        }
    }
}
