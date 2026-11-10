# Source import status

The original v0.1.3 source ZIP and APK are preserved outside this repository. **This branch does not yet contain the full Android source or binary dependencies.**

Do not publish or install an APK until:

- Full source, Alpine rootfs, and native runtime libraries are imported and verified against the preserved archive.
- Task dispatch, real chat, watcher, Hands, task states, and previews are tested.
- A persistent signing key is configured securely; a newly generated debug key cannot update the installed APK.
- APK package name, versionCode, and signature are checked against the installed app.
- Android device testing verifies the agent does actual work and never reports success merely on acknowledgement.

The build workflow is expected to fail until the complete source is present; this is intentional rather than a successful APK release.
