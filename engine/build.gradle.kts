import java.io.File

plugins {
	id("com.android.library")
	id("kotlin-android")
}

// The data-driven checkout: the submodule when initialized, else the sibling shared checkout,
// else an explicit -Pnyora.dataDrivenDir=<path>.
val dataDrivenDir: File = listOfNotNull(
	(findProperty("nyora.dataDrivenDir") as String?)?.let(::File),
	rootProject.file("data"),
	rootProject.file("../nyora-shared-datadriven/data"),
).firstOrNull { File(it, "engine/EngineRegistry.kt").isFile }
	?: error("nyora-data-driven checkout not found: run `git submodule update --init` or pass -Pnyora.dataDrivenDir")

// AGP 9 rejects Providers in the source-set API, so the generated roots are plain directories and
// every task that reads them is wired to the sync tasks below by hand.
val engineSourcesDir: File = layout.buildDirectory.dir("generated/engine-src").get().asFile
val engineAssetsDir: File = layout.buildDirectory.dir("generated/engine-assets").get().asFile

val syncEngineSources by tasks.registering(Sync::class) {
	from(File(dataDrivenDir, "engine")) { into("engine") }
	from(File(dataDrivenDir, "src/main/kotlin")) {
		into("src")
		exclude("app/nyora/data/runtime/**")   // CLI harness and JVM-only HTTP context
	}
	into(engineSourcesDir)
}

val syncEngineAssets by tasks.registering(Sync::class) {
	from(File(dataDrivenDir, "catalogue.json"), File(dataDrivenDir, "blocked-sources.json"))
	into(File(engineAssetsDir, "nyora"))
}

android {
	namespace = "com.nyora.hasan72341.engine"
	compileSdk = 36
	defaultConfig {
		minSdk = 23
		consumerProguardFiles("consumer-rules.pro")
	}
	buildTypes {
		create("nightly") { initWith(getByName("release")) }
	}
	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	kotlinOptions { jvmTarget = "17" }
	sourceSets["main"].kotlin.srcDir(engineSourcesDir)
	sourceSets["main"].assets.srcDir(engineAssetsDir)
	lint {
		abortOnError = true
		// Engine sources are vendored; only API-level and correctness classes matter here.
		disable += setOf(
			"UnusedResources",
			"GradleDependency",
			"NewerVersionAvailable",
			"OldTargetApi",
			"DefaultLocale",
			"SimpleDateFormat",
			"Aligned16KB",
		)
	}
}

// Every consumer of the generated roots must run the sync first; source-set directories added as
// plain files carry no task dependency of their own.
val engineSyncTasks = setOf(syncEngineSources.name, syncEngineAssets.name)
tasks.matching { task ->
	task.name !in engineSyncTasks && (
		task.name.startsWith("compile") ||
			task.name.contains("lint", ignoreCase = true) ||
			task.name.contains("asset", ignoreCase = true) ||
			task.name.contains("source", ignoreCase = true)
		)
}.configureEach {
	dependsOn(syncEngineSources, syncEngineAssets)
}

dependencies {
	coreLibraryDesugaring(libs.desugar.jdk.libs)
	api(platform(libs.okhttp.bom))
	api(libs.okhttp)
	api(libs.jsoup)
	api(libs.kotlinx.coroutines.android)
}
