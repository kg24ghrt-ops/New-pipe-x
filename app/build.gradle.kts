plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.opt.new.pipe.x"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opt.new.pipe.x"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// --------------------------------------------------------------------------- #
// Extraction backends
// --------------------------------------------------------------------------- #
// Both versions are pinned in config/dependencies.toml and mirrored here by
// tools/update-dependencies.py (dependency update workflow).  They are read
// from gradle.properties so the two backends can be bumped without touching
// this file.
val newpipeExtractorVersion: String = (providers.gradleProperty("newpipeExtractorVersion").orNull ?: "0.0.0")
    .trim().removeSurrounding("\"")
val ytDlpVersion: String = (providers.gradleProperty("ytDlpVersion").orNull ?: "0.0.0")
    .trim().removeSurrounding("\"")

/** NewPipe Extractor pulls in these transitive coordinates; they are declared
 *  explicitly so `./gradlew checkDependencies` can verify the pins below. */
val newpipeExtractorTransitives = setOf(
    "com.github.TeamNewPipe:nanojson",
    "org.jsoup:jsoup",
    "com.google.code.findbugs:jsr305",
    "com.google.protobuf:protobuf-javalite",
    "org.mozilla:rhino",
    "org.mozilla:rhino-engine",
)

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Lightweight / native Android extraction path (NewPipeExtractor backend).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newpipeExtractorVersion")

    // Python/Chaquopy extraction path.  yt-dlp itself is *not* a Maven
    // dependency: it is installed into the Chaquopy build environment from
    // app/python-requirements.txt, which must pin the same release as the
    // ytDlpVersion property above (enforced by :app:checkDependencies).
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

/**
 * Guard for the dependency update workflow.
 *
 * Fails the build when a backend version declared in gradle.properties drifts
 * away from config/dependencies.toml, when the yt-dlp requirement is not pinned
 * exactly, or when NewPipeExtractor stops publishing one of its known
 * transitive coordinates.
 */
val checkDependencies by tasks.registering {
    group = "verification"
    description = "Verify that the NewPipeExtractor and yt-dlp pins agree across the project files."

    val pinFile = rootProject.file("config/dependencies.toml")
    val requirementsFile = file("python-requirements.txt")
    inputs.file(pinFile)
    inputs.file(requirementsFile)
    inputs.property("newpipeExtractorVersion", newpipeExtractorVersion)
    inputs.property("ytDlpVersion", ytDlpVersion)

    doLast {
        val failures = mutableListOf<String>()

        // 1. gradle.properties <-> config/dependencies.toml
        val pins = mutableMapOf<String, MutableMap<String, String>>()
        var section: String? = null
        pinFile.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") && line.endsWith("]") ->
                    section = line.removeSurrounding("[", "]").trim()
                line.isNotEmpty() && !line.startsWith("#") && "=" in line && section != null -> {
                    val (key, value) = line.substringBefore("=").trim() to
                        line.substringAfter("=").trim().removeSurrounding("\"")
                    pins.getOrPut(section!!) { mutableMapOf() }[key] = value
                }
            }
        }
        mapOf(
            "newpipeextractor" to newpipeExtractorVersion,
            "yt_dlp" to ytDlpVersion,
        ).forEach { (name, declared) ->
            val pinned = pins[name]?.get("current")
            if (pinned == null) {
                failures += "config/dependencies.toml has no [$name] current version"
            } else if (pinned != declared) {
                failures += "$name: gradle.properties says $declared but config/dependencies.toml pins $pinned"
            }
        }

        // 2. the Chaquopy requirement must pin exactly the yt-dlp version
        val requirement = requirementsFile.takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.trimStart().startsWith("yt-dlp==") }
        when {
            requirement == null ->
                failures += "app/python-requirements.txt does not pin yt-dlp==<version>"
            !requirement.contains("==$ytDlpVersion") ->
                failures += "yt-dlp: app/python-requirements.txt pins ${requirement.trim()} but gradle.properties says $ytDlpVersion"
        }

        // 3. NewPipeExtractor must still resolve with its known transitives
        val extractor = configurations.detachedConfiguration(
            dependencies.create("com.github.TeamNewPipe:NewPipeExtractor:$newpipeExtractorVersion")
        ).apply { isTransitive = true }
        val resolved = try {
            (extractor as org.gradle.api.artifacts.LenientConfiguration).allModuleDependencies
        } catch (error: Exception) {
            failures += "NewPipeExtractor:$newpipeExtractorVersion could not be resolved: ${error.message}"
            emptyList()
        }
        val coordinates = resolved.map { "${it.moduleGroup}:${it.moduleName}" }.toSet()
        val missing = newpipeExtractorTransitives - coordinates
        if (resolved.isNotEmpty() && missing.isNotEmpty()) {
            failures += "NewPipeExtractor:$newpipeExtractorVersion no longer brings $missing - review the ProGuard rules and the workflow's verification step"
        }

        if (failures.isNotEmpty()) {
            failures.forEach { logger.error("dependency pin: $it") }
            error("${failures.size} dependency pin problem(s) found; run ./tools/update-dependencies.py --write")
        }
        logger.lifecycle(
            "Dependency pins OK: NewPipeExtractor $newpipeExtractorVersion, yt-dlp $ytDlpVersion " +
                "(${resolved.size} extractor transitive coordinates)"
        )
    }
}

tasks.named("check") { dependsOn(checkDependencies) }
