plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-image-export-toolkit"
    group = "io.github.uw-loci"
    version = "0.7.2"
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

    testImplementation(libs.bundles.qupath)
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.jfree:org.jfree.svg:5.0.6")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
}
