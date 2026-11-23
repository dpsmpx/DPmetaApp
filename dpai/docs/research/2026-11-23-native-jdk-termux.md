# Android-native JDK for APK builds inside DPmetaApp (2026-11-23)

## Reproduced failure and cause of earlier non-fixes

User device: installed `targetSdk=28`; Alpine `openjdk21-jdk` fails at
HotSpot `check_pax` with `Failed to mark memory page as executable`. A separate
`mmap(RW) -> mprotect(RWX) -> mprotect(RX)` probe returns errno 0, so a SELinux
or PaX diagnosis is *not proven* by this message. The probe and the JVM differ
in their runtime (Alpine/musl versus Android/Bionic) and startup code.

Termux does not use Alpine's `openjdk21-jdk`: its OpenJDK is built using Android
NDK/Bionic, with Android-specific patches. It uses `/system/bin/linker64` and
`/apex` system libraries. Hard-coded Termux paths prevent copying it directly
into `/root` or an arbitrary other app prefix.

Primary sources:

- https://github.com/termux/termux-packages/blob/master/packages/openjdk-21/build.sh
- https://github.com/termux/termux-packages/wiki/Building-packages
- https://github.com/termux/termux-app/blob/master/app/build.gradle
- https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java

## Implementation

`AndroidNativeJdk` installs a checksum-pinned official Termux bootstrap in a
**private virtual prefix** within DPmetaApp's Alpine rootfs. The PRoot guest
exposes `/data/data/com.termux/files/usr` for binary compatibility, but it is
*not* the actual filesystem or UID of the installed Termux app. It preserves
relative links from `SYMLINKS.txt`, executable permissions for `apt`, and uses
APT's repository signatures to install Termux `openjdk-21` and its dependencies.
System `/system`, `/apex`, `/vendor` and `/linkerconfig` are bound for Bionic's loader.

The first on-device run verified the bootstrap checksum but stopped at
`DPAI_NATIVE_JDK_APT_UPDATE_FAILED`, immediately after Termux's exact message
`Ability to run this command as root has been disabled permanently for safety purposes.`
This is explained by DPmetaApp's existing global PRoot `-0`: Alpine `apk`
needs the emulated root identity, but Termux's patched `apt-get` explicitly
rejects it. The native JDK installer now carries an internal
`ProotHost.REAL_USER_MARKER`; its *one* PRoot invocation omits `-0` and executes
as the actual Android app UID. The marker is stripped by the wrapper and cannot
leak to `/bin/sh`. Other processes, including `apk`, retain `-0` unchanged.
Source confirmation:
https://github.com/termux/termux-packages/wiki/Termux-file-system-layout
and https://github.com/termux/termux-app/issues/2689.
The concurrently printed `linkerconfig/ld.config.txt` warning is not by itself
evidence of why APT refused UID 0; evaluate separately if it remains fatal.

The installer is invoked by both the Android SDK UI and
`environment.android_sdk` automation. It must successfully execute native
`java -version`, `javac -version` and a real Java compile/run round-trip before
declaring the JDK installed.
Android build profiles receive `JAVA_HOME` and `PATH` for that runtime and
`ANDROID_HOME`/`ANDROID_SDK_ROOT` for the SDK. Preflight checks concrete
Android toolchain executables before the first Gradle command. Alpine Java
remains available for non-Android Java tasks.

## Acceptance still required on Android hardware

1. Fresh installation on a phone (arm64-v8a) with the primary `targetSdk=28`
   DPmetaApp APK; install Linux, then Android SDK from the app itself.
2. Confirm `DPAI_NATIVE_JDK_OK` and `DPAI_ANDROID_SDK_OK`; inspect
   `environment.probe` for `android_java`, `android_javac`, and `android_sdk`.
3. Run a Gradle APK profile within the same app, not an SSH/Termux target.
   Confirm nonempty APK, AAPT badging and package/signature verification.
4. Check that stopping/restarting the app and upgrading its APK retain the
   managed toolchain without breaking Gradle.

Linux/JVM unit tests and a green GitHub Actions build do **not** establish
that Bionic JDK works through PRoot on this device. Until step 3 is observed,
do not mark on-device Gradle/APK builds as fixed. If PRoot itself blocks the
native HotSpot, the executor must run the native toolchain outside PRoot with
an app-specific prefix, requiring the Termux package set to be rebuilt for
`com.dpsmpx.dpai` (not relabeled or copied from an incompatible prefix).
