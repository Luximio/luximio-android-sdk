plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

group = "app.luximio"
version = "0.1.0-rc.1"

// Keep one reviewed copy of each notice and include it in binary/source archives.
abstract class PrepareSdkNotices : DefaultTask() {
    @get:InputFile abstract val licenseFile: RegularFileProperty
    @get:InputFile abstract val noticeFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.dir("META-INF/app.luximio/luximio-links-android").get().asFile
        directory.mkdirs()
        licenseFile.get().asFile.copyTo(directory.resolve("LICENSE"), overwrite = true)
        noticeFile.get().asFile.copyTo(directory.resolve("NOTICE"), overwrite = true)
    }
}

val prepareSdkNotices = tasks.register<PrepareSdkNotices>("prepareSdkNotices") {
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    noticeFile.set(layout.projectDirectory.file("NOTICE"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sdk-notices"))
}

android {
    namespace = "app.luximio.links"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}
androidComponents.onVariants { variant ->
    requireNotNull(variant.sources.resources).addGeneratedSourceDirectory(
        prepareSdkNotices, PrepareSdkNotices::outputDirectory
    )
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    // These types occur in public signatures; consumers need them at compile time.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    testImplementation("junit:junit:4.13.2")
}

val reviewDocuments = tasks.register<Zip>("reviewDocuments") {
    archiveClassifier.set("review-docs")
    archiveBaseName.set("luximio-links-android")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("review-docs"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(layout.projectDirectory.file("README.md"))
    from(layout.projectDirectory.file("PACKAGING_NOTICE.md"))
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.file("NOTICE"))
    from(layout.projectDirectory.dir("docs")) {
        include("*.md")
        into("docs")
    }
}

tasks.withType<Jar>().configureEach {
    if (name == "sourceReleaseJar") {
        from(prepareSdkNotices)
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "luximio-links-android"
            pom {
                name.set("Luximio Links Android")
                description.set("Deferred deep link recovery for Android using Google Play Install Referrer and Play Integrity.")
                url.set("https://luximio.com")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                scm {
                    url.set("https://github.com/Luximio/luximio-android-sdk")
                    connection.set("scm:git:https://github.com/Luximio/luximio-android-sdk.git")
                }
            }
            // AGP creates this component after project evaluation.
            afterEvaluate { from(components["release"]) }
            artifact(reviewDocuments)
        }
    }
    repositories {
        maven {
            name = "LocalCandidate"
            url = uri(layout.buildDirectory.dir("local-maven"))
        }
    }
}
