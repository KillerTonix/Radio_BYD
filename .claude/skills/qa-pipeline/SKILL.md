---
name: qa-pipeline
description: Run the Radio_BYD QA agent pipeline on the BYD_DiLink_API29 emulator (build/install, then the ui-explorer, audio-session-tester and test-writer-healer agents). Use when asked to run QA, the agent pipeline, or any of those three agents.
---
# QA agent pipeline

1. Build, install and launch the car debug APK on the running `BYD_DiLink_API29` emulator, and confirm the app process and its media session exist (`dumpsys media_session`).
2. **ui-explorer**: map screens and selectors in landscape 1920x1080 and portrait 1080x1920. Cover tabs, the Armenia / Russia / Germany station lists, search, favorites persistence across force-stop and relaunch, the Music (local & USB) flow with pushed test MP3s, and the key log. Writes `test-results/exploration-report.md`.
3. **audio-session-tester**: check `dumpsys media_session` / `dumpsys audio` states, rapid switching and the debounce, wheel keyevents (85/87/88/126/127/166/167) with the app in the foreground and the background, dead-stream and network-loss recovery, shuffle, audio focus, and lifecycle. Writes `test-results/audio-session-report.md`.
4. **test-writer-healer**: extend the JVM and instrumented suites from the reports above, run both, fix *test-side* failures, and re-run until green. Writes `test-results/test-suite-report.md`.

The agent definitions in `~/.claude/agents/` were written for a web app (HTMLMediaElement, Playwright, `localStorage`).
When you start them, give them the Android context from `CLAUDE.md`. `ui-explorer` only has Playwright, Read, Glob and Grep,
so it can neither drive the emulator nor write its report. Run its brief with a general-purpose agent, or add
`mcp__android-emulator__*`, `Bash` and `Write` to its tools. The emulator is shared, so run the agents one after another.
