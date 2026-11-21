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

// One project, which is the library: JitPack serves it as
// com.github.LaabamOne-Business-Solutions-Pvt-Ltd:cyberrakshak-monitor-android:<tag>.
rootProject.name = "cyberrakshak-monitor-android"
