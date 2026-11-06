package com.cosmic.ai.core.project

/** Toolchain candidates to probe. This catalog never implies that a runtime is installed. */
data class RuntimeDefinition(
    val language: String,
    val executables: List<String>,
    val versionExecutables: List<String> = executables.take(1),
    val buildExecutables: List<String> = emptyList(),
    val testExecutables: List<String> = emptyList()
)

data class RuntimeInspection(
    val languageDetected: Boolean,
    val runtimeInstalled: Boolean,
    val executable: String?,
    val version: String?,
    val availableExecutables: Set<String>,
    val executionSupported: Boolean,
    val buildSupported: Boolean,
    val testSupported: Boolean
)

object RuntimeCatalog {
    val definitions: List<RuntimeDefinition> = listOf(
        RuntimeDefinition("Python", listOf("python3", "python", "pip", "pytest"), listOf("python3", "python"), listOf("pip"), listOf("pytest")),
        RuntimeDefinition("JavaScript", listOf("node", "npm", "pnpm", "yarn"), listOf("node"), listOf("npm", "pnpm", "yarn"), listOf("npm", "pnpm", "yarn")),
        RuntimeDefinition("TypeScript", listOf("node", "tsc", "npm", "pnpm", "yarn"), listOf("node"), listOf("tsc", "npm", "pnpm", "yarn"), listOf("tsc", "npm", "pnpm", "yarn")),
        RuntimeDefinition("Rust", listOf("rustc", "cargo"), listOf("rustc", "cargo"), listOf("cargo"), listOf("cargo")),
        RuntimeDefinition("Go", listOf("go"), listOf("go"), listOf("go"), listOf("go")),
        RuntimeDefinition("Java", listOf("java", "javac", "gradle", "mvn"), listOf("java"), listOf("gradle", "mvn"), listOf("gradle", "mvn")),
        RuntimeDefinition("Kotlin", listOf("kotlin", "kotlinc", "gradle", "mvn"), listOf("kotlin", "kotlinc"), listOf("gradle", "mvn", "kotlinc"), listOf("gradle", "mvn")),
        RuntimeDefinition("Dart", listOf("dart", "flutter"), listOf("dart"), listOf("dart", "flutter"), listOf("dart", "flutter")),
        RuntimeDefinition("C", listOf("cc", "gcc", "clang", "cmake"), listOf("cc", "gcc", "clang"), listOf("cmake", "cc", "gcc", "clang"), listOf("ctest")),
        RuntimeDefinition("C++", listOf("c++", "g++", "clang++", "cmake"), listOf("c++", "g++", "clang++"), listOf("cmake", "c++", "g++", "clang++"), listOf("ctest")),
        RuntimeDefinition("C#", listOf("dotnet"), listOf("dotnet"), listOf("dotnet"), listOf("dotnet")),
        RuntimeDefinition("PHP", listOf("php", "composer"), listOf("php"), listOf("composer"), listOf("composer", "phpunit")),
        RuntimeDefinition("Ruby", listOf("ruby", "bundle", "rake"), listOf("ruby"), listOf("bundle", "rake"), listOf("rake", "rspec")),
        RuntimeDefinition("Swift", listOf("swift", "swiftc"), listOf("swift", "swiftc"), listOf("swift", "swiftc"), listOf("swift"))
    )

    fun forLanguage(language: String): RuntimeDefinition? = definitions.firstOrNull { it.language.equals(language, true) }

    fun unsupported(language: String, detected: Boolean): RuntimeInspection = RuntimeInspection(
        languageDetected = detected, runtimeInstalled = false, executable = null, version = null,
        availableExecutables = emptySet(), executionSupported = false, buildSupported = false, testSupported = false
    )
}
