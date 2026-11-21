import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library") version "8.13.1"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    `maven-publish`
}

// JitPack serves a tag as com.github.LaabamOne-Business-Solutions-Pvt-Ltd:cyberrakshak-monitor-android:<tag>, so the
// tag is this version exactly (1.0.0, no "v"). The console's scripts/sdk-packages.mjs --check reads this line.
group = "com.github.LaabamOne-Business-Solutions-Pvt-Ltd"
version = "1.0.0"

android {
    namespace = "ai.cyberrakshak.monitor"
    compileSdk = 36

    defaultConfig {
        // The library uses nothing newer than API 21 (Context, Build.VERSION, Log, java.net and java.util.concurrent).
        minSdk = 21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Built with Kotlin 2.2, readable by apps on Kotlin 1.9 and later; Java 8 bytecode, as the console compiles it.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        // The tests are the console's own (tests/sdk/android/MonitorTest.kt), compiled there with -nowarn.
        compilerOptions.suppressWarnings.set(true)
    } else {
        // The library itself: no warnings, as the console's tests compile it (-Werror).
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // The tests start test/fixtures/fake-server.mjs with Node.js (127.0.0.1 only), unless CR_TEST_SERVER names one.
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "cyberrakshak-monitor-android"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Cyber Rakshak error monitoring for Android")
                description.set("Uncaught exceptions on any thread, written at once and sent at the next start, scrubbed of secrets, to your site's Errors page in the Cyber Rakshak console.")
                url.set("https://app.cyberrakshak.ai/sdk/README.md")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://app.cyberrakshak.ai/sdk/LICENSE")
                    }
                }
                organization {
                    name.set("Laabam One Business Solutions (OPC) Private Limited")
                }
                scm {
                    url.set("https://github.com/LaabamOne-Business-Solutions-Pvt-Ltd/cyberrakshak-monitor-android")
                    connection.set("scm:git:https://github.com/LaabamOne-Business-Solutions-Pvt-Ltd/cyberrakshak-monitor-android.git")
                }
            }
        }
    }
}
