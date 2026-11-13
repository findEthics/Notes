What this repo does:
`Notes/` is a single-activity Jetpack Compose / Material 3 Notes & To-dos app for E-Ink and hardware-keyboard devices such as the Unihertz Titan 2 Elite, with notes and tasks stored in SQLite schema v2. Grace Launcher uses signature-verified `NotesCaptureProvider` IPC for quick capture. Expected load: one local user on Android API 31+ doing interactive keyboard/touch editing while Grace Launcher may issue concurrent binder quick-capture calls.

## Must fix

- [x] **1. `append_note` uses an unlocked read-modify-write across SQLite connections** (`app/src/main/java/com/example/notes/NotesCaptureProvider.kt:L88-L95`; `app/src/main/java/com/example/notes/DatabaseHelper.kt:L99-L129`)
  - **What this is:** The provider reads a note, appends captured text, then writes the result through the database helper.
  - **Problem:** Two concurrent binder calls can read the same old text, then the later write replaces the earlier append.
  - **Fix:** Do the read and append in one SQLite write transaction on one connection, or use an atomic SQL update; add a concurrent-append test.
  - **If we skip it:** Concurrent Grace Launcher captures can silently overwrite one another.

- [x] **2. Overlapping quick-capture intents can overwrite `pendingCapture`** (`app/src/main/java/com/example/notes/MainActivity.kt:L393-L398`)
  - **What this is:** `MainActivity` keeps an incoming capture in a single `pendingCapture` slot until it is consumed.
  - **Problem:** A second intent arriving before consumption replaces the first capture.
  - **Fix:** Queue or serialize captures until each is applied exactly once; test two rapid capture intents.
  - **If we skip it:** One of two quick captures can disappear without notice.

- [ ] **3. Process death can leave empty note rows and unclaimed recovery drafts** (`app/src/main/java/com/example/notes/MainActivity.kt:L577,L647`; `app/src/main/java/com/example/notes/DraftRecovery.kt:L16-L30`)
  - **Intentionally skipped:** Startup deletion is unsafe: restored `note/{id}` routes still own placeholder rows, and deleting recovery files breaks `DraftRecoveryProcessTest`. No destructive reconciliation added.
  - **What this is:** New-note flows create an empty database row and draft-recovery data that normal editor lifecycle paths later save or clean up.
  - **Problem:** If Android kills the process before those paths run, an empty row or draft file can remain without an active editor or recovery claim.
  - **Fix:** Reconcile placeholder rows and recovery files on restart, and add a process-death test for new blank and nonblank notes.
  - **If we skip it:** Empty notes can accumulate and a saved draft can remain stranded instead of reopening with its note.

- [x] **4. `create_note` takes the title from the first whitespace-delimited word** (`app/src/main/java/com/example/notes/NotesCaptureProvider.kt:L80-L84`)
  - **What this is:** The provider derives the title from captured note text.
  - **Intentionally skipped / kept as-is:** Per user instruction and the existing `NotesCaptureProviderTest` contract, text without a newline keeps first-word title extraction (`Buy groceries` becomes title `Buy`). No first-line-title change was implemented; the checkbox remains `[x]` as marked by the user.

## Should fix

- [ ] **5. `commitPendingDelete()` blocks the main thread on the DB executor** (`app/src/main/java/com/example/notes/MainActivity.kt:L532-L549`, called from `L411,L553,L565,L591,L598`)
  - **Intentionally skipped:** A fire-and-forget async replacement breaks the synchronous success/ordering contract required by its six callers (including staging another deletion). A safe redesign is outside this fix.
  - **What this is:** The main thread waits on a database-executor future with `.get()` while committing a pending delete.
  - **Problem:** A slow or queued database operation stalls input and can make list interactions freeze.
  - **Fix:** Commit asynchronously and return the result to the main thread, preserving pending-delete and undo ordering; test with a delayed DB operation.
  - **If we skip it:** Slow storage or concurrent writes can leave the UI unresponsive during deletion.

- [x] **6. Backup is enabled while the backup-rule XML files are unreferenced** (`app/src/main/AndroidManifest.xml:L7`; `app/src/main/res/xml/backup_rules.xml`; `app/src/main/res/xml/data_extraction_rules.xml`)
  - **What this is:** The manifest sets `allowBackup="true"`, but does not connect either rules file to Android backup.
  - **Problem:** Android ignores unreferenced rules, so backup and restore follow platform defaults rather than any policy those stubs appear to describe.
  - **Fix:** Choose the intended note-backup policy, then reference the applicable rules with `fullBackupContent` / `dataExtractionRules`, or remove the stubs if default backup is deliberate.
  - **If we skip it:** Users may be surprised by note backup or restore behavior, and intended exclusions will not apply.

- [x] **7. `NoteScreen` reloads all notes on resume and data changes** (`app/src/main/java/com/example/notes/NoteScreen.kt:L199,L213-L236,L263-L279`)
  - **Validated correction:** It loads only the current note. Skip only the redundant first resume refresh; retain later resume/data-change refreshes so external appends remain visible.
  - **What this is:** Resume and data-change callbacks trigger full note reloads.
  - **Problem:** The app repeats database loading and decoding even when the current data is already fresh; the work grows with the note collection.
  - **Fix:** Skip the unconditional resume reload when cached data is current, and reload only after a relevant data change.
  - **If we skip it:** Returning to the screen can do needless work and become slower as notes accumulate.

- [x] **8. Grace Launcher catches `Throwable` and swallows coroutine cancellation** (`../GraceLauncher/app/src/main/java/com/galaxyrio/gracelauncher/platform/NotesCapture.kt:L23,L34`)
  - **What this is:** `NotesCapture` handles failures with a broad `Throwable` catch.
  - **Problem:** The catch also handles `CancellationException`, so canceled coroutine work is treated like an ordinary capture failure.
  - **Fix:** Re-throw `CancellationException` before handling other failures, keeping the existing binder-error handling; add a cancellation test.
  - **If we skip it:** Canceled capture work can continue or report a misleading failure to its caller.

- [x] **9. The 10,000-character limit can split a UTF-16 surrogate pair** (`app/src/main/java/com/example/notes/NotesCaptureProvider.kt:L74`; `app/src/main/java/com/example/notes/QuickCapture.kt:L16`)
  - **What this is:** Capture text is truncated at a fixed UTF-16 character index.
  - **Problem:** If an emoji's two-code-unit surrogate pair straddles that index, the stored text contains only half of it.
  - **Fix:** Truncate at a code-point boundary in both capture paths; test a supplementary character at the limit.
  - **If we skip it:** A capture at that boundary can corrupt or replace its final emoji.

## Nice to have

- [x] **10. `QuickCapture.NEW_TODO` and `CreationShortcut.TASK` routing branches are unreachable** (`app/src/main/java/com/example/notes/MainActivity.kt:L582,L645-L654`)
  - **What this is:** These branches include the unreachable `"task?id=-1"` route.
  - **Problem:** Dead paths make unsupported routing look live and add code future changes must maintain.
  - **Fix:** Remove only the unreachable enum and routing branches, retaining the working to-do capture route.
  - **If we skip it:** Maintainers may rely on or update a path that cannot run.

- [x] **11. Timestamp SQL and checkbox-prefix checks are duplicated** (`app/src/main/java/com/example/notes/DatabaseHelper.kt:L247-L258`; `app/src/main/java/com/example/notes/NotesCaptureProvider.kt:L31`; `app/src/main/java/com/example/notes/NoteEditor.kt:L68`; `app/src/main/java/com/example/notes/NoteScreen.kt:L330`)
  - **What this is:** Timestamp bumps and checkbox-prefix rules are repeated across database and UI code.
  - **Problem:** The same behavior must be corrected in multiple places and can drift.
  - **Fix:** Keep one timestamp-update path and one checkbox-prefix rule, then reuse them at each caller.
  - **If we skip it:** A future fix can update one copy but leave another with different behavior.

- [x] **12. Manual cursor/database closing and an unused select icon add cleanup work** (`app/src/main/java/com/example/notes/DatabaseHelper.kt:L67-L119`; `app/src/main/res/drawable/ic_select_notes.xml`)
  - **What this is:** `getNotePreviews` and `getNoteById` close cursors/databases manually, and `ic_select_notes.xml` has no references in `src/` or `res/`.
  - **Problem:** Manual cleanup is easier to miss as code changes, and the unreferenced drawable is dead resource code. `ic_add_note.xml` and `ic_edit_note.xml` are used by `res/xml/shortcuts.xml:L3,L11` and should remain.
  - **Fix:** Use `use {}` for the cursor/database resources and delete only `ic_select_notes.xml`.
  - **If we skip it:** Resource cleanup stays harder to review and the unused icon remains in the project.

Verdict: valid audit items #1, #2, #6, #7, #8, #9, #10, #11 and #12, plus the Titan 2 Elite `keyboard|keyboardHidden` manifest fix, are implemented and host-verified by the checks below. These cover atomic provider appends, ordered capture queue with editor-attachment wait, backup-rule wiring, cancellation rethrows, first-resume refresh suppression, surrogate-safe truncation, dead-route removal, and shared timestamp/checkbox helpers and resource cleanup. #3 and #5 remain skipped; #4 remains checked but intentionally unchanged per user instruction and the existing provider test contract. Device acceptance is limited as noted below.

Grace Launcher: retain explicit cancellation rethrows; replace the Robolectric runner/dependency with a throwing Context fake using default-returning local Android stubs (zero added dependencies). URI initialization is lazy so the fake throws inside each operation's try/catch before any URI access; this tests the explicit cancellation rethrow, not merely pre-cancelled dispatch.

Validation (2026-11-13, JDK 21): `./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:testDebugUnitTest :app:testReleaseUnitTest :app:compileDebugAndroidTestKotlin` passed. Notes: 25 unit tests per variant, zero failures/skips (including two capture-boundary tests); lint: 48 warnings per variant, zero errors. Grace Launcher `bash ./gradlew :app:testDebugUnitTest` passed: 207 tests, zero failures/skips (including both cancellation tests). `git diff --check` clean in both repositories. Logs: `../runtime-audit/notes-audit-fixes-host.log` and `../runtime-audit/grace-audit-fixes-host.log`.

Unchecked: added provider-concurrency and same-frame capture instrumentation tests compiled but were not executed; physical Titan Tab behavior, backup/restore, and the broader lifecycle/process-restart device matrix remain unverified. No device installs or database fixtures were run during the host-only validation above. Subsequent Notes debug update-installs on emulator-5558/5560 succeeded; Titan was absent. The signed release SHA-256 is `12832fa2e469c377db3f291d92571a02adf8ef1ff677683f8d26abf4e4da9b1f`; signature/certificate and digest evidence: `../runtime-audit/final-install-status-20261113.md`. Installs do not establish the unchecked acceptance. Checked boxes indicate implemented or explicitly retained behavior, not device acceptance. The timestamp helper intentionally retains the existing String/TEXT format, not Long, to preserve legacy ordering without migration.
