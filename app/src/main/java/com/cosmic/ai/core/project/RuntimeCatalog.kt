package com.cosmic.ai.core.project

/** Toolchain candidates to probe. Availability is established separately by the platform adapter. */
data class RuntimeDefinition(val language: String, val executables: List<String>)

object RuntimeCatalog {
    val definitions: List<RuntimeDefinition> = listOf(
        RuntimeDefinition("Python", listOf("python3", "python", "pip", "pytest")),
        RuntimeDefinition("JavaScript", listOf("node", "npm", "pnpm", "yarn")),
        RuntimeDefinition("TypeScript", listOf("node", "tsc", "npm", "pnpm", "yarn")),
        RuntimeDefinition("Rust", listOf("rustc", "cargo")),
        RuntimeDefinition("Go", listOf("go")),
        RuntimeDefinition("Java", listOf("java", "javac", "gradle", "mvn")),
        RuntimeDefinition("Kotlin", listOf("kotlin", "kotlinc", "gradle", "mvn")),
        RuntimeDefinition("Dart", listOf("dart", "flutter")),
        RuntimeDefinition("C", listOf("cc", "gcc", "clang")),
        RuntimeDefinition("C++", listOf("c++", "g++", "clang++")),
        RuntimeDefinition("C#", listOf("dotnet")),
        RuntimeDefinition("PHP", listOf("php", "composer")),
        RuntimeDefinition("Ruby", listOf("ruby", "bundle")),
        RuntimeDefinition("Swift", listOf("swift", "swiftc"))
    )

    fun forLanguage(language: String): RuntimeDefinition? = definitions.firstOrNull { it.language.equals(language, true) }
}
