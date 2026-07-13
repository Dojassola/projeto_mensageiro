// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Copy>("buildCompleto") {
    group = "build"
    description = "Gera o APK release pronto para instalar e compartilhar."
    dependsOn(":app:assembleRelease")
    from("app/build/outputs/apk/release/app-release.apk")
    into(layout.buildDirectory.dir("distribuicao"))
    rename { "Mensageiro.apk" }
}
