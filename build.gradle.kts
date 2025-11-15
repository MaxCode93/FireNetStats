// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // The following line for kotlin.compose is commented out in your original file,
    // but if you need it, this is the correct syntax.
    // alias(libs.plugins.kotlin.compose) apply false
}
