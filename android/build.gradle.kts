// Video2 — Android port of the iOS app (com.ahcrazy.video2)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Surface Kotlin/Gradle failures in the job's check annotations as well as the full log.
// This also helps when an environment can reach GitHub's Checks API but not the log archive.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    val errors = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    val capture = org.gradle.api.logging.StandardOutputListener { text ->
        text.toString().lineSequence().map { it.trim() }.filter { line ->
            line.startsWith("e: ") || line.startsWith("error: ") ||
                line.startsWith("Caused by: ") || line.contains("minCompileSdk") ||
                line.contains("Could not resolve") || line.contains(" FAILED")
        }.take(30).forEach { if (errors.size < 50) errors.add(it.take(600)) }
    }
    allprojects {
        logging.addStandardOutputListener(capture)
        logging.addStandardErrorListener(capture)
        tasks.configureEach {
            logging.addStandardOutputListener(capture)
            logging.addStandardErrorListener(capture)
        }
    }
    @Suppress("DEPRECATION")
    gradle.buildFinished {
        val failure = failure
        if (failure != null) {
            val diagnostics = (errors.toList() + generateSequence(failure) { it.cause }.take(7).mapNotNull { it.message })
                .distinct().joinToString("\n").take(12_000)
                .replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            println("::error title=Android build diagnostics::$diagnostics")
        }
    }
}
