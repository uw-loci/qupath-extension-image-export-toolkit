plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
    id("com.github.spotbugs") version "6.5.0"
}

qupathExtension {
    name = "qupath-extension-image-export-toolkit"
    group = "io.github.uw-loci"
    version = "1.2.0"
    description = "QuIET - QuPath Image Export Toolkit. Comprehensive export of rendered overlays, label masks, raw pixel data, and ML training tiles with wizard UI, script generation, and batch processing."
    automaticModule = "io.github.uw-loci.extension.quiet"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow("org.jfree:org.jfree.svg:5.0.6")
    // Gson for the JSON export-recipe (ExportRecipe save/load).
    shadow("com.google.code.gson:gson:2.13.2")

    testImplementation(libs.bundles.qupath)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.jfree:org.jfree.svg:5.0.6")
    testImplementation("com.google.code.gson:gson:2.13.2")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection (gates the build)
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}
