# CLAUDE.md

Guidance for Claude Code sessions (including cloud-hosted sessions at claude.ai) working in this repository.

## What this project is

zrAuto is an Android media player for Android Auto (and Android TV), forked from Andrey
Pavlenko's **Fermata Auto**. It's a Gradle multi-module Android project written mostly in
Java, with the app's core living in the `fermata` module and optional features split into
`modules/*`.

## Code and layout structure

```
zrAuto/
├── fermata/                  # Main app module
│   ├── build.gradle
│   ├── lib/auto/             # Vendored .aar/.jar deps (Android Auto, Xposed API)
│   └── src/
│       ├── main/java/me/aap/fermata/
│       │   ├── action/       # Key/input action handling (Action, Key, KeyEventHandler)
│       │   ├── addon/        # Pluggable add-ons (each modules/* addon hooks in here)
│       │   ├── media/        # Media library/session/metadata models
│       │   ├── provider/     # Content/media providers
│       │   ├── service/      # Background services (playback service, etc.)
│       │   ├── ui/
│       │   │   ├── activity/ # Activities
│       │   │   ├── fragment/ # Fragments (screens: browser, favorites, settings, ...)
│       │   │   └── view/     # Custom views/widgets
│       │   ├── util/         # Helpers
│       │   └── vfs/          # Virtual file system layer (local, network providers)
│       ├── main/res/         # Layouts, drawables, values (many locale value-* dirs)
│       ├── auto/             # Android Auto–specific source set (manifest, java, res, assets)
│       └── test/             # Unit tests
├── modules/                  # Optional feature add-ons, each its own Gradle module
│   ├── cast/                 # Chromecast support
│   ├── chat/                 # Chat/LLM-assistant UI (ChatListView, etc.)
│   ├── exoplayer/            # ExoPlayer playback engine
│   ├── felex/                # FelexAddon (flashcard/vocab-style feature)
│   ├── gdrive/                # Google Drive VFS provider (needs google-services.json)
│   ├── mlkit/                # ML Kit translation addon
│   ├── opusmt/                # OpusMT on-device translation
│   ├── poi/                  # POI/native-loader addon
│   ├── sftp/                 # SFTP VFS provider
│   ├── smb/                  # SMB/CIFS VFS provider
│   ├── tv/                   # Android TV specific UI (floating button mediator, etc.)
│   ├── vlc/                  # VLC playback engine
│   ├── web/                  # In-app browser addon (WebBrowserFragment)
│   └── whisper/              # Whisper speech-to-text (native/CMake build, slow in CI)
├── depends/utils/            # Git submodule: shared `me.aap` utils library (own Gradle module)
├── docker/                   # Dockerfile/entrypoint for containerized builds
├── res/                      # Root-level release resources (fermata.jks, build_jks.gradle)
├── build.sh                  # Local build script (produces APK/AAB into ./dist)
├── settings.gradle           # Wires up :fermata, :utils, and every modules/* as Gradle modules
├── gradle/libs.versions.toml # Version catalog (app version, AGP, NDK, AndroidX libs, etc.)
└── .github/workflows/        # build-apk.yml, build-abi.yml (reusable), release.yml
```

Conventions worth knowing:
- Package root for first-party code is `me.aap.fermata` (app) and `me.aap.utils` (submodule).
- Each `modules/*` add-on generally mirrors the app's package layout (`me.aap.fermata.addon.<name>`
  or `me.aap.fermata.<layer>.<name>`) and is registered into the addon system under
  `fermata/.../addon/`.
- `settings.gradle` auto-discovers every directory under `modules/` as a Gradle module, and
  conditionally excludes `:gdrive` when Google Services aren't configured (`NO_GS=true`).
- `depends/utils` is a **git submodule** — always clone/pull with `--recurse-submodules`, and be
  aware a fresh checkout without submodules initialized will fail to build.
- Locale/translation resources live under `fermata/src/main/res/values-<locale>/` — don't hand-edit
  translated strings unless specifically asked; app strings normally originate in `values/strings.xml`.

## This app cannot be built in this (cloud) session

Claude Code sessions hosted at claude.ai run in an ephemeral container **without the Android SDK,
NDK, or emulator**, and generally without network access to Google's Maven/SDK manager. This means:

- Do **not** attempt `./gradlew`, `./build.sh`, or any Android build/test task here — it will fail
  or hang on missing `ANDROID_SDK_ROOT`, missing NDK, or network-restricted dependency downloads.
- Do **not** try to install the Android SDK or download it in this container to "make it work" —
  it's not a supported path for this environment.
- Verify changes by **reading the code carefully** (types, imports, resource references, XML/Java
  syntax) instead of compiling. Cross-check callers/usages with Grep before assuming a change is safe.
- The **real build/verification happens in GitHub Actions** after you push (see below) — that's the
  first point a change actually gets compiled, so treat CI as your test loop for anything you can't
  verify by inspection alone.
- If a task genuinely requires a local build to verify (e.g. suspected native/CMake issue), say so
  explicitly rather than claiming the build passed.

## After committing: check GitHub Actions, and fix failures

This repo builds on every push via `.github/workflows/build-apk.yml` (which fans out to
`build-abi.yml` for arm64, and armv7 only on `master`). After you push a commit or branch:

1. Look up the workflow run for your branch/commit (`mcp__github__actions_list` with
   `method: list_workflow_runs`, filtered by branch, or `mcp__github__list_commits` to confirm the
   pushed SHA) — owner/repo is `yusairiyap/zrauto` (this session's scope).
2. Builds take several minutes (whisper's native CMake step alone is ~5-7 min per the comment in
   `build-abi.yml`), so it won't be done immediately. Use `ScheduleWakeup` to check back periodically
   (a few minutes apart) instead of polling in a tight loop or sleeping inline.
3. If the run **fails**: pull the failing job's logs with `mcp__github__get_job_logs`
   (`failed_only: true` for the run, or a specific `job_id`), diagnose the root cause from the log
   output, fix it in code, commit, push, and re-check the new run. Don't guess blindly — read the
   actual error (compile error, resource conflict, lint/test failure, etc.).
4. If the run **succeeds**, you're done — report that CI is green.
5. Never bypass a failure by disabling the check, skipping a module, or force-pushing over history to
   "reset" CI — fix the underlying cause.

## When in doubt, ask

This is someone's personal Android project (not a work codebase) with real users on the release
channel. If a task is ambiguous, touches release signing/keystore config (`res/fermata.jks`,
`res/build_jks.gradle`, CI secrets), changes what ships in `master`/tagged releases, or could affect
translated strings across many locales, **ask the user before proceeding** rather than guessing.
