# FileSync (Android)

A small folder-synchronisation app in the spirit of FreeFileSync: pick two folders,
compare them, review what will happen, then synchronise.

## Features
- Pick left / right folder (Storage Access Framework, no storage permission needed; choice is remembered)
- Compare by relative path, size and modification time (2 s tolerance)
- Three modes
  - **Two-way ↔**: newer file wins; deletions are propagated for files synced before; changes on both sides = conflict (tap the row to choose a direction)
  - **Mirror →**: right becomes an exact copy of left (extra files on right are deleted)
  - **Update →**: copy new/changed files left → right, never delete
- Preview list with per-file include/exclude checkbox
- Exclude filter (`*.tmp, .thumbnails, Thumbs.db`)
- Confirmation before deleting, progress bar, result summary

## Compatibility
Targets Android 16 (API 36), runs on Android 8.0 (API 26) and newer. Pure Kotlin, so one APK works on every CPU type (ARM, x86).

## Build the APK
**Option A – Android Studio (Ladybug or newer)**
1. File → Open → select this `FileSyncApp` folder; let Gradle sync.
2. Build → Build Bundle(s) / APK(s) → Build APK(s).
3. APK: `app/build/outputs/apk/debug/app-debug.apk`

**Option B – command line** (JDK 17, Android SDK 36, Gradle 8.11.1)
`gradle :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
(release is signed with the debug key, so it installs directly)

**Option C – GitHub, no installation**
Upload this folder to a new GitHub repository. The workflow in `.github/workflows/build-apk.yml`
builds the APK automatically: open the repo → Actions → Build APK → download the `FileSync-apk` artifact.

## Install
Copy the APK to the phone, open it, and allow "install unknown apps" for your file manager or browser when asked.

## Notes / limitations
- Android's file API does not let apps set a file's modified time, so copied files get "now" as time.
  The app stores a small record of every synced pair (in app storage) so those files still count as identical.
  Clearing app data resets this history; the next two-way run then falls back to "newer wins".
- Files are overwritten in place (no temp-file + rename), so don't interrupt a sync of very large files.
- Empty folders are not synchronised, only files.
- Not meant for folders with hundreds of thousands of files.
