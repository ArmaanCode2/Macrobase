import com.android.apksig.ApkVerifier
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

// Release key from the environment (set by ../launch/release.ps1). Android Studio's "Generate Signed APK"
// wizard passes android.injected.signing.* instead, which AGP applies on its own and which wins.
val envKeystorePath: String? = providers.environmentVariable("KEYSTORE_PATH").orNull
val envStorePassword: String? = providers.environmentVariable("STORE_PASSWORD").orNull
val envKeyPassword: String? = providers.environmentVariable("KEY_PASSWORD").orNull

android {
    namespace = "com.macrobase.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.macrobase.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only when a key is provided. Nothing is checked here: a missing key only stops the release
        // packaging step (checkReleaseSigning), so sync, debug builds and unit tests need no key.
        if (!envKeystorePath.isNullOrEmpty() && !envStorePassword.isNullOrEmpty()) {
            create("release") {
                storeFile = file(envKeystorePath)
                storePassword = envStorePassword
                keyAlias = "upload"
                keyPassword = envKeyPassword.takeUnless { it.isNullOrEmpty() } ?: envStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No debug-key fallback: without a release key the APK is left unsigned and
            // checkReleaseSigning stops the packaging step with an explanation.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Zero-network invariant (AGENTS.md): no merged manifest may request INTERNET or register
// Google's CCT upload backend, including entries added transitively by dependencies.
tasks.withType<com.android.build.gradle.tasks.ProcessApplicationManifest>().configureEach {
    val manifestFile = mergedManifest
    doLast {
        val merged = manifestFile.get().asFile
        val text = merged.readText()
        if (Regex("""<uses-permission[^>]*"android\.permission\.INTERNET"""").containsMatchIn(text)) {
            throw GradleException("${merged.path} requests android.permission.INTERNET; MacroBase must stay offline")
        }
        if (text.contains("CctBackendFactory")) {
            throw GradleException("${merged.path} registers the datatransport CCT upload backend; MacroBase must stay offline")
        }
    }
}

// Room writes every schema version to app/schemas. Keep each JSON in version control: the
// migration tests build old databases from them (BUG-046).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// ---------------------------------------------------------------------------------------------
// Release signing guard. Runs only on the release packaging path (packageRelease / assembleRelease),
// never at configuration time, so IDE sync, debug builds and unit tests need no key.
// An update installs over the existing app (and keeps its data) only when it is signed with the same
// key, so every release APK must be signed by the one key pinned in macrobase.releaseCertSha256.
// ---------------------------------------------------------------------------------------------

/** Fails before the release APK is packaged when no release key was provided. */
abstract class CheckReleaseSigningTask : DefaultTask() {
    @get:Input @get:Optional abstract val injectedStoreFile: Property<String>
    @get:Input abstract val injectedComplete: Property<Boolean>
    @get:Input @get:Optional abstract val envKeystorePath: Property<String>
    @get:Input abstract val envStorePasswordSet: Property<Boolean>

    @TaskAction
    fun check() {
        val injected = injectedStoreFile.orNull
        if (!injected.isNullOrEmpty()) {
            if (!injectedComplete.get()) {
                throw GradleException(
                    "Release signing: Android Studio passed a keystore ($injected) but not all of store password, " +
                        "key alias and key password. Fill in every field of the Generate Signed APK wizard."
                )
            }
            if (!File(injected).isFile) throw GradleException("Release signing: keystore not found: $injected")
            return
        }
        val envPath = envKeystorePath.orNull
        if (!envPath.isNullOrEmpty() && envStorePasswordSet.get()) {
            if (!File(envPath).isFile) throw GradleException("Release signing: KEYSTORE_PATH points to a missing file: $envPath")
            return
        }
        throw GradleException(
            """
            |No release signing key was provided, so the release APK will not be packaged.
            |MacroBase release builds are never signed with the debug key. Either:
            |  - double-click launch/release.cmd (next to this project), or
            |  - set KEYSTORE_PATH and STORE_PASSWORD (and KEY_PASSWORD if it differs) and run assembleRelease again.
            """.trimMargin()
        )
    }
}

/** Never the Android debug certificate, and the certificate SHA-256 must equal macrobase.releaseCertSha256. */
object ReleaseSignerPin {
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    fun check(file: File, cert: X509Certificate, pinnedSha256: String?) {
        val subject = cert.subjectX500Principal.name
        val actual = sha256(cert)
        val actualColons = actual.chunked(2).joinToString(":")
        if (subject.contains("CN=Android Debug")) {
            throw GradleException("$file is signed with the Android debug certificate ($subject). Use the release key.")
        }
        val pinned = pinnedSha256?.lowercase()?.filter { it.isLetterOrDigit() }
        if (pinned.isNullOrEmpty()) {
            throw GradleException(
                """
                |macrobase.releaseCertSha256 is not set, so the release signer cannot be confirmed. Nothing was published.
                |The file is signed by: $subject
                |Its certificate SHA-256 is: $actualColons
                |Before pasting anything, compare it with the "SHA256:" line (keytool prints it in capitals) from
                |  keytool -list -v -keystore <your release .jks file> -alias upload
                |Only if the two are identical, add this line to gradle.properties:
                |macrobase.releaseCertSha256=$actual
                |If they differ, do NOT paste it: this build was signed with a different key than your release key.
                """.trimMargin()
            )
        }
        if (pinned != actual) {
            throw GradleException(
                "Release signer mismatch: $file is signed by $subject with SHA-256 $actualColons, " +
                    "but macrobase.releaseCertSha256 is $pinned. Wrong key; nothing was published."
            )
        }
    }
}

/**
 * Reads the signer of the packaged release APK, rejects the debug certificate and anything that does not
 * match macrobase.releaseCertSha256, then publishes the APK as build/release-verified/MacroBase.apk. On any
 * failure the packaged APK it read is deleted as well, so no wrongly signed release APK is left behind.
 */
abstract class VerifyReleaseApkTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkFolder: DirectoryProperty
    @get:Internal abstract val artifactsLoader: Property<BuiltArtifactsLoader>
    @get:Input @get:Optional abstract val pinnedSha256: Property<String>
    @get:OutputFile abstract val outputApk: RegularFileProperty

    @TaskAction
    fun verify() {
        // A failed check must not leave an older MacroBase.apk behind that looks freshly published.
        val target = outputApk.get().asFile
        target.delete()
        val builtArtifacts = artifactsLoader.get().load(apkFolder.get())
            ?: throw GradleException("No release APK found in ${apkFolder.get().asFile}")
        val packaged = builtArtifacts.elements.map { File(it.outputFile) }
        try {
            val apk = packaged.singleOrNull()
                ?: throw GradleException("Expected exactly one release APK, found ${packaged.size}")
            val result = try {
                ApkVerifier.Builder(apk).build().verify()
            } catch (e: Exception) {
                throw GradleException("Could not read the signature of $apk: ${e.message}", e)
            }
            if (!result.isVerified) {
                throw GradleException("$apk is not validly signed: ${result.allErrors.joinToString("; ")}")
            }
            val cert = result.signerCertificates.singleOrNull()
                ?: throw GradleException("$apk must have exactly one signer, found ${result.signerCertificates.size}")
            ReleaseSignerPin.check(apk, cert, pinnedSha256.orNull)
            apk.copyTo(target, overwrite = true)
            val actualColons = ReleaseSignerPin.sha256(cert).chunked(2).joinToString(":")
            logger.lifecycle("Release APK verified (signer SHA-256 $actualColons) and copied to $target")
        } catch (e: Exception) {
            target.delete()
            packaged.forEach { apk ->
                if (apk.delete()) logger.error("Deleted the rejected release APK $apk")
            }
            throw e
        }
    }
}

val checkReleaseSigning = tasks.register<CheckReleaseSigningTask>("checkReleaseSigning") {
    group = "verification"
    description = "Fails the release APK packaging when no release signing key was provided."
    fun isSet(name: String) = providers.gradleProperty(name).map { it.isNotEmpty() }.orElse(false)
    injectedStoreFile.set(providers.gradleProperty("android.injected.signing.store.file"))
    injectedComplete.set(
        isSet("android.injected.signing.store.password")
            .zip(isSet("android.injected.signing.key.alias")) { a, b -> a && b }
            .zip(isSet("android.injected.signing.key.password")) { a, b -> a && b }
    )
    envKeystorePath.set(providers.environmentVariable("KEYSTORE_PATH"))
    envStorePasswordSet.set(providers.environmentVariable("STORE_PASSWORD").map { it.isNotEmpty() }.orElse(false))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val verifyApk = tasks.register<VerifyReleaseApkTask>("verify${variantName}Apk") {
            group = "verification"
            description = "Checks the release APK signer against macrobase.releaseCertSha256 and copies it to build/release-verified/MacroBase.apk."
            apkFolder.set(apkDir)
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            pinnedSha256.set(providers.gradleProperty("macrobase.releaseCertSha256"))
            outputApk.set(layout.buildDirectory.file("release-verified/MacroBase.apk"))
        }
        // No release APK is packaged or signed without a release key.
        tasks.named { it == "package$variantName" }.configureEach {
            dependsOn(checkReleaseSigning)
            // verifyReleaseApk deletes a rejected APK, which Gradle's file-system snapshot doesn't see.
            // A missing APK must never count as up to date, or the next build would skip packaging it.
            outputs.upToDateWhen { apkDir.get().asFile.listFiles()?.any { f -> f.name.endsWith(".apk") } == true }
        }
        tasks.named { it == "assemble$variantName" }.configureEach { dependsOn(verifyApk) }
    }
}

dependencies {
    implementation(project(":ppocr-sdk"))
    // AndroidX & Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM & UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX (Hardware-assisted capture)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Google ML Kit On-Device Text Recognition (Bundled offline model)
    implementation(libs.mlkit.text.recognition)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
