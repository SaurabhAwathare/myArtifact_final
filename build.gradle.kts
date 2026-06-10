plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.perf) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// Custom task to interface with Firebase CLI
tasks.register<Exec>("firebase") {
    group = "firebase"
    description = "Run Firebase CLI commands. Usage: ./gradlew firebase --args='deploy --only firestore:rules'"
    
    // On Windows, use 'firebase.cmd' if 'firebase' is not in path
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val command = if (isWindows) "firebase.cmd" else "firebase"
    
    commandLine(command)
    
    if (project.hasProperty("args")) {
        val argsProperty = project.property("args").toString()
        args(argsProperty.split(" "))
    }
}

tasks.register<Exec>("login") {
    group = "firebase"
    description = "Log into Firebase CLI"
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val command = if (isWindows) "firebase.cmd" else "firebase"
    commandLine(command, "login")
}
