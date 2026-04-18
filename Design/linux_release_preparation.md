# Linux Release Preparation

Preparation notes for the Linux build of Asareon Raam. Not scheduled yet; filed for a future session.

## Why Linux next

Windows-only alpha limits the launch surface. Show HN, r/LocalLLaMA, r/selfhosted, and Lobsters are off-limits until Linux ships. Kotlin/Compose channels (r/Kotlin, Kotlin Weekly, Kotlin Slack `#feed`) work now without a Linux build; AI-world channels don't.

## Blockers

**The app is not nearly as polished and functional to warrant big "marketing" push at this point.**

## VM setup

- **Virtualization:** VirtualBox. Free, open source, no account required. VMware Workstation Pro is free as of Nov 2024 but requires a Broadcom portal account — disqualified.
- **Guest distro:** Ubuntu 24.04 LTS. Baseline for `.deb`, what reviewers will use, LTS until 2029.
- **VM specs:** 8 GB RAM, 4+ vCPUs, 60 GB dynamic disk, accelerated 3D enabled.
- **Snapshot** after clean Ubuntu + JDK 21 + Git. Revert-to-clean is the big time-saver for packaging tests.
- **Guest additions** (`virtualbox-guest-utils`) for shared folder + clipboard. Drop built artifacts into the VM without scp.

## Scope of Linux alpha

Target: a Linux build that installs, launches, and runs without Windows-specific regressions. Not feature parity with the Windows chrome.

1. **Skip custom title bar on Linux.** Use WM-drawn decorations. `WindowsSnapHelper` → `expect`/`actual` with a Linux no-op composable. Wayland gives clients minimal decoration control; don't fight it.
2. **XDG config paths.** `BootConfig.kt` currently assumes Windows paths. Read `$XDG_CONFIG_HOME` with fallback to `~/.config/asareon-raam/`. Same pattern for `$XDG_STATE_HOME` and `$XDG_DATA_HOME`.
3. **Platform-specific UI audit.** File dialogs, tray icons, notifications — any Windows-native path needs a Linux route or graceful fallback.
4. **Filesystem sandboxing review.** Java NIO is portable by default; audit anything touching Windows ACLs or attributes in per-agent FS access.
5. **Packaging.** `jpackage` produces `.deb` natively on Linux hosts. For alpha, ship `.deb` + `.tar.gz`. `.rpm` and AppImage can wait.
6. **CI.** GitHub Actions, `ubuntu-latest` runner, `./gradlew packageDistributionForCurrentOS` on release tags. Matrix Windows + Linux in one workflow.
7. **README known-issues section.** List Windows-only features explicitly. Buys goodwill; reduces low-value bug reports.

## Smoke test checklist

Run these on a clean Ubuntu 24.04 VM before calling the alpha shippable:

- Install `.deb`. No missing-dependency errors.
- App launches. No crashes from JNA or WM calls.
- Window chrome renders via the WM.
- Config writes to `~/.config/asareon-raam/` (or `$XDG_CONFIG_HOME` if set).
- Boot console renders. Logs stream live.
- Create a session, dispatch a command, get a response through a gateway.
- Ollama works against `localhost:11434` — high-value test; Ollama users skew Linux.
- Backup creates a valid zip. Restore works.
- Clean uninstall via `apt remove`.

## Out of scope

- **macOS.** Separate effort — notarization, codesigning, Apple Silicon vs Intel, `.dmg`/`.pkg`. Don't bundle with Linux.
- **Custom chrome on Linux.** 1.1+ concern.
- **Distro coverage beyond Ubuntu.** Fedora pass is a v1.2 concern (different glibc, GTK, `.rpm`, SELinux — catches real bugs but not for alpha).

## Launch plan, post-Linux

Phase 2 submission list, gated on Linux alpha release:

- Show HN, Tuesday–Thursday 8–10am ET. Be present for comments the first 2–3 hours.
- r/LocalLLaMA.
- r/selfhosted.
- Lobsters, if invite available.
- Awesome lists: awesome-ai-agents, awesome-llm-apps, awesome-selfhosted.

Before submitting: GIF/screencap at top of README showing boot console + UI. One-sentence pitch leading with *what it does*, not *how it's built*.
