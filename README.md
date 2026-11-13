# The Last Fuck — Android Agent

Repository initialized for preserving v0.1.3 and developing a separate repair branch.

**Status:** Repository scaffolding only. The original source archive has not yet been imported. No repaired APK has been built or published.

Repair goals: working chat, real task dispatch and verification, visible progress and previews, proactive Watcher, existing Hands integration, and terminal validation.

Do not treat an acknowledged task as completed without execution evidence.

## ZIP runner

In **Files → Import file / ZIP**, select a web app ZIP from your phone or a document provider. Select the imported ZIP, tap **Run ZIP**, then choose an HTML entry (index.html is listed first). The live player runs JavaScript and serves relative CSS, images, modules and WASM from that extracted project. Use **Reload** or **Close** in the player. Reopen Run ZIP to choose another entry.

Imports use a new `projects/zip-*` folder every time and preserve the original ZIP. Archives are limited to 5,000 entries and 200 MB compressed/expanded; traversal and duplicate paths are rejected. The player has no Android bridge, file/content access or external network access. ZIPs must contain built HTML output; Node/Python servers and unbuilt source projects do not run in this web player. Device testing, including Godot threads and WebView compatibility, remains required.
