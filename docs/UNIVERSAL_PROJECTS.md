# Multi-language project support status

COSMIC's Android client is written in Kotlin, but project technology detection is language-neutral pure Kotlin and uses SAF-granted project entries as evidence.

## Currently detected

Source extensions include Python, JavaScript, TypeScript, Java, Kotlin, C/C++, C#, Go, Rust, PHP, Ruby, Swift, Dart, HTML, CSS/SCSS, SQL, shell, PowerShell, YAML, JSON, TOML, and Terraform/HCL. Recognized manifests include Python dependency files, `package.json`, `tsconfig.json`, `Cargo.toml`, `go.mod`, Maven/Gradle, `pubspec.yaml`, Composer, Gemfile, Docker, and Terraform indicators. Some framework names are identified only when dependency manifests are readable and contain their dependency declaration.

Project scans use the user-selected Storage Access Framework tree. Traversal is bounded to five directory levels and 500 entries; manifest reads are capped. Detection results are evidence, not a claim of full project understanding.

## Runtime availability

The Android adapter probes fixed executable names using the app's visible shell PATH. Android's application sandbox normally does not include the development toolchains users may have in another terminal app. COSMIC reports executable availability only when the probe finds it. It does not use Termux binaries, remote Linux, or containers unless a separate supported integration is implemented.

The runtime catalog is descriptive. It does not automatically execute a guessed build/run command. Project-specific command selection, dependency installation, isolated execution, remote/container runners, full syntax-aware edits, formatters, and language-specific test orchestration remain incomplete.

## Product experience status

Conversation navigation, drawer organization, Activity visibility, provider setup entry points, contextual terminal access, explicit text-file attachment, code-fence display/copy, theme selection, and project technology summaries are implemented in the Android UI. The current message renderer is intentionally limited and is not full CommonMark or syntax highlighting. Image previews, rich tables/link rendering, per-project stored instructions/memory, and a persistent workflow engine/scheduler are not implemented yet.
