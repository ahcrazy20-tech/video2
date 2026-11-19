pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Video2"
include(":app")

// Keep dependency, script and task-configuration failures visible when CI log archives
// cannot be downloaded. Unit test and native compiler failures already report annotations.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    gradle.buildFinished(object : org.gradle.api.Action<org.gradle.BuildResult> {
        override fun execute(result: org.gradle.BuildResult) {
            val failure = result.failure ?: return
            val detail = generateSequence(failure) { it.cause }.take(6).mapNotNull { it.message }
                .distinct().joinToString("\n").take(3500)
                .replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            println("::error title=Android Gradle failure::$detail")
        }
    })
}
