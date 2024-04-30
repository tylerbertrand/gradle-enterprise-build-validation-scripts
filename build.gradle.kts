@file:Suppress("HasPlatformType")

import com.felipefzdz.gradle.shellcheck.Shellcheck
import org.gradle.crypto.checksum.Checksum

plugins {
    id("base")
    id("com.felipefzdz.gradle.shellcheck") version "1.4.6"
    id("com.github.breadmoirai.github-release") version "2.5.2"
    id("org.gradle.crypto.checksum") version "1.4.0"
}

group = "com.gradle"

repositories {
    maven {
        name = "Solutions"
        url = uri("https://repo.gradle.org/artifactory/solutions")
        credentials {
            username = providers
                .environmentVariable("GRADLE_SOLUTIONS_REPOSITORY_USERNAME")
                .orElse(providers.gradleProperty("gradleSolutionsRepositoryUsername"))
                .get()
            password = providers
                .environmentVariable("GRADLE_SOLUTIONS_REPOSITORY_PASSWORD")
                .orElse(providers.gradleProperty("gradleSolutionsRepositoryPassword"))
                .get()
        }
        authentication {
            create<BasicAuthentication>("basic")
        }
        content {
            includeModule("com.gradle.enterprise", "build-scan-summary")
        }
    }
    exclusiveContent {
        forRepository {
            ivy {
                url = uri("https://github.com/matejak/")
                patternLayout {
                    artifact("[module]/archive/refs/tags/[revision].[ext]")
                }
                metadataSources {
                    artifact()
                }
            }
        }
        filter {
            includeModule("argbash", "argbash")
        }
    }
    mavenCentral()
}

val isDevelopmentRelease = !hasProperty("finalRelease")
val releaseVersion = releaseVersion()
val releaseNotes = releaseNotes()
val distributionVersion = distributionVersion()
val buildScanSummaryVersion = "0.9-2023.2"

allprojects {
    version = releaseVersion.get()
}

val argbash by configurations.creating
val commonComponents by configurations.creating
val mavenComponents by configurations.creating

dependencies {
    argbash("argbash:argbash:2.10.0@zip")
    commonComponents("com.gradle.enterprise:build-scan-summary:${buildScanSummaryVersion}")
    mavenComponents(project(":configure-gradle-enterprise-maven-extension"))
    mavenComponents("com.gradle:gradle-enterprise-maven-extension:1.18.4")
    mavenComponents("com.gradle:common-custom-user-data-maven-extension:1.13")
}

shellcheck {
    additionalArguments = "-a -x"
    shellcheckVersion = "v0.8.0"
}

val unpackArgbash by tasks.registering(Copy::class) {
    group = "argbash"
    description = "Unpacks Argbash."
    from(zipTree(argbash.singleFile)) {
        // All files in the zip are under an "argbash-VERSION/" directory. We only want everything under this directory.
        // We can remove the top-level directory while unpacking the zip by dropping the first directory in each file's relative path.
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("argbash"))
}

val generateBashCliParsers by tasks.registering(ApplyArgbash::class) {
    group = "argbash"
    description = "Uses Argbash to generate Bash command line argument parsing code."
    argbashHome.set(layout.dir(unpackArgbash.map { it.outputs.files.singleFile }))
    scriptTemplates.set(fileTree("components/scripts") {
        include("**/*-cli-parser.m4")
        exclude("gradle/.data/")
        exclude("maven/.data/")
    })
    supportingTemplates.set(fileTree("components/scripts") {
        include("**/*.m4")
        exclude("gradle/.data/")
        exclude("maven/.data/")
    })
}

val copyGradleScripts by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the Gradle source and the generated scripts to the output directory."

    // local variable required for configuration cache compatibility
    // https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:not_yet_implemented:accessing_top_level_at_execution
    val releaseVersion = releaseVersion
    val buildScanSummaryVersion = buildScanSummaryVersion

    inputs.property("project.version", releaseVersion)
    inputs.property("summary.version", buildScanSummaryVersion)

    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.dir("release").file("version.txt"))
    rename("version.txt", "VERSION")

    from(layout.projectDirectory.dir("components/scripts/gradle")) {
        exclude("gradle-init-scripts")
        filter { line: String -> line.replace("<HEAD>", releaseVersion.get()) }
        filter { line: String -> line.replace("<SUMMARY_VERSION>", buildScanSummaryVersion) }
    }
    from(layout.projectDirectory.dir("components/scripts/gradle")) {
        include("gradle-init-scripts/**")
        into("lib/")
    }
    from(layout.projectDirectory.dir("components/scripts")) {
        include("README.md")
        include("mapping.example")
        include("network.settings")
        include("lib/**")
        exclude("lib/cli-parsers")
        filter { line: String -> line.replace("<HEAD>", releaseVersion.get()) }
        filter { line: String -> line.replace("<SUMMARY_VERSION>", buildScanSummaryVersion) }
    }
    from(generateBashCliParsers.map { it.outputDir.file("lib/cli-parsers/gradle") }) {
        into("lib/")
    }
    from(commonComponents) {
        into("lib/build-scan-clients/")
    }
    into(layout.buildDirectory.dir("scripts/gradle"))
}

val copyMavenScripts by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the Maven source and the generated scripts to the output directory."

    // local variable required for configuration cache compatibility
    // https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:not_yet_implemented:accessing_top_level_at_execution
    val releaseVersion = releaseVersion
    val buildScanSummaryVersion = buildScanSummaryVersion

    inputs.property("project.version", releaseVersion)
    inputs.property("summary.version", buildScanSummaryVersion)

    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.dir("release").file("version.txt"))
    rename("version.txt", "VERSION")

    from(layout.projectDirectory.dir("components/scripts/maven")) {
        filter { line: String -> line.replace("<HEAD>", releaseVersion.get()) }
        filter { line: String -> line.replace("<SUMMARY_VERSION>", buildScanSummaryVersion) }
    }
    from(layout.projectDirectory.dir("components/scripts/")) {
        include("README.md")
        include("mapping.example")
        include("network.settings")
        include("lib/**")
        exclude("lib/cli-parsers")
        filter { line: String -> line.replace("<HEAD>", releaseVersion.get()) }
        filter { line: String -> line.replace("<SUMMARY_VERSION>", buildScanSummaryVersion) }
    }
    from(generateBashCliParsers.map { it.outputDir.file("lib/cli-parsers/maven") }) {
        into("lib/")
    }
    from(commonComponents) {
        into("lib/build-scan-clients/")
    }
    from(mavenComponents) {
        into("lib/maven-libs/")
    }
    into(layout.buildDirectory.dir("scripts/maven"))
}

val assembleGradleScripts by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages the Gradle experiment scripts in a zip archive."
    archiveBaseName.set("gradle-enterprise-gradle-build-validation")
    archiveFileName.set(archiveBaseName.flatMap { a -> distributionVersion.map { v -> "$a-$v.zip" } })
    from(copyGradleScripts)
    into(archiveBaseName.get())
}

val assembleMavenScripts by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages the Maven experiment scripts in a zip archive."
    archiveBaseName.set("gradle-enterprise-maven-build-validation")
    archiveFileName.set(archiveBaseName.flatMap { a -> distributionVersion.map { v -> "$a-$v.zip" } })
    from(copyMavenScripts)
    into(archiveBaseName.get())
}

tasks.assemble {
    dependsOn(assembleGradleScripts, assembleMavenScripts)
}

val consumableGradleScripts by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("gradle-build-validation-scripts"))
}

val consumableMavenScripts by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("maven-build-validation-scripts"))
}

artifacts {
    add(consumableGradleScripts.name, assembleGradleScripts)
    add(consumableMavenScripts.name, assembleMavenScripts)
}

val shellcheckGradleScripts by tasks.registering(Shellcheck::class) {
    group = "verification"
    description = "Perform quality checks on Gradle build validation scripts using Shellcheck."
    sourceFiles = copyGradleScripts.get().outputs.files.asFileTree.matching {
        include("**/*.sh")
        // scripts in lib/ are checked when Shellcheck checks the top-level scripts because the top-level scripts include (source) the scripts in lib/
        exclude("lib/")
    }
    // scripts in lib/ are still inputs to this task (we want shellcheck to run if they change) even though we don't include them explicitly in sourceFiles
    inputs.files(copyGradleScripts.get().outputs.files.asFileTree.matching {
        include("lib/**/*.sh")
    }).withPropertyName("libScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = layout.buildDirectory.file("scripts/gradle").get().asFile
    reports {
        html.outputLocation.set(layout.buildDirectory.file("reports/shellcheck-gradle/shellcheck.html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/shellcheck-gradle/shellcheck.xml"))
        txt.outputLocation.set(layout.buildDirectory.file("reports/shellcheck-gradle/shellcheck.txt"))
    }
}

val shellcheckMavenScripts by tasks.registering(Shellcheck::class) {
    group = "verification"
    description = "Perform quality checks on Maven build validation scripts using Shellcheck."
    sourceFiles = copyMavenScripts.get().outputs.files.asFileTree.matching {
        include("**/*.sh")
        // scripts in lib/ are checked when Shellcheck checks the top-level scripts because the top-level scripts include (source) the scripts in lib/
        exclude("lib/")
    }
    // scripts in lib/ are still inputs to this task (we want shellcheck to run if they change) even though we don't include them explicitly in sourceFiles
    inputs.files(copyMavenScripts.get().outputs.files.asFileTree.matching {
        include("lib/**/*.sh")
    }).withPropertyName("libScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    workingDir = layout.buildDirectory.file("scripts/maven").get().asFile
    reports {
        html.outputLocation.set(layout.buildDirectory.file("reports/shellcheck-maven/shellcheck.html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/shellcheck-maven/shellcheck.xml"))
        txt.outputLocation.set(layout.buildDirectory.file("reports/shellcheck-maven/shellcheck.txt"))
    }
}

tasks.check {
    dependsOn(shellcheckGradleScripts, shellcheckMavenScripts)
}

val generateChecksums by tasks.registering(Checksum::class) {
    group = "distribution"
    description = "Generates checksums for the distribution zip files."
    inputFiles.setFrom(assembleGradleScripts, assembleMavenScripts)
    outputDirectory.set(layout.buildDirectory.dir("distributions/checksums").get().asFile)
    checksumAlgorithm.set(Checksum.Algorithm.SHA512)
}

githubRelease {
    token((findProperty("github.access.token") ?: System.getenv("GITHUB_ACCESS_TOKEN") ?: "").toString())
    owner.set("gradle")
    repo.set("gradle-enterprise-build-validation-scripts")
    targetCommitish.set("main")
    releaseName.set(gitHubReleaseName())
    tagName.set(gitReleaseTag())
    prerelease.set(isDevelopmentRelease)
    overwrite.set(isDevelopmentRelease)
    generateReleaseNotes.set(false)
    body.set(releaseNotes)
    releaseAssets(assembleGradleScripts, assembleMavenScripts, generateChecksums.map { it.outputs.files.asFileTree })
}

val createReleaseTag by tasks.registering(CreateGitTag::class) {
    // Ensure tag is created only after a successful build
    mustRunAfter("build")
    tagName.set(githubRelease.tagName.map { it.toString() })
    overwriteExisting.set(isDevelopmentRelease)
}

tasks.githubRelease {
    dependsOn(createReleaseTag)
}

fun gitHubReleaseName(): Provider<String> {
    return releaseVersion.map { if (isDevelopmentRelease) "Development release" else it }
}

fun gitReleaseTag(): Provider<String> {
    return releaseVersion.map { if (isDevelopmentRelease) "development-latest" else "v$it" }
}

fun distributionVersion(): Provider<String> {
    return releaseVersion.map { if (isDevelopmentRelease) "dev" else it }
}

fun releaseVersion(): Provider<String> {
    val versionFile = layout.projectDirectory.file("release/version.txt")
    return providers.fileContents(versionFile).asText.map { it.trim() }
}

fun releaseNotes(): Provider<String> {
    val releaseNotesFile = layout.projectDirectory.file("release/changes.md")
    return providers.fileContents(releaseNotesFile).asText.map { it.trim() }
}
