use dioxus::prelude::*;

mod floating;

fn main() {
    dioxus::launch(App);
}

#[component]
fn App() -> Element {
    rsx! {
        document::Title { "象棋" }
        document::Stylesheet { href: asset!("/assets/main.css") }

        main { class: "app",
            section { class: "welcome",
                div { class: "piece", aria_hidden: "true", "象" }
                floating::StartButton {}
                floating::SettingsButton {}
            }
        }
    }
}
