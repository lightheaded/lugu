# Building without a JDK on the machine

Some machines that hold this checkout cannot compile it. A Mac with no JDK and no Android
Studio has the Android SDK, `adb` and an AVD, and no `java` at all — so `./gradlew`
cannot start. The same machine can still build, test and record screenshots through a
container.

This page is that recipe. It is also the environment [screenshots.md](screenshots.md) and
[AGENTS.md](../../AGENTS.md) ask for, because a Roborazzi baseline recorded on macOS never
pixel-matches the `ubuntu-latest` runner.

## The image

The container must match the CI runner: `eclipse-temurin:21-jdk-jammy` on `linux/amd64`,
Android SDK `platforms;android-37.0` and `build-tools;37.0.0`.

```dockerfile
FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      unzip curl git ca-certificates && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools && \
    curl -sSLo tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip && \
    unzip -q tools.zip && mv cmdline-tools latest && rm tools.zip
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true

# The published command-line tools predate API 37. Let them upgrade themselves first, or
# the next line fails with "Failed to find package 'platforms;android-37.0'".
RUN sdkmanager "cmdline-tools;latest" > /dev/null && yes | sdkmanager --licenses > /dev/null 2>&1 || true
ENV PATH=$ANDROID_HOME/cmdline-tools/latest-2/bin:$PATH

RUN sdkmanager --sdk_root=$ANDROID_HOME \
      "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" > /dev/null
ENV GRADLE_USER_HOME=/gradle
WORKDIR /work
```

Build it once:

```sh
docker build --platform linux/amd64 -t lugu-build:1 .
```

Two traps are worth naming, because both cost time:

- **The bundled `sdkmanager` cannot install API 37.** The newest published command-line
  tools zip is older than the platform this project compiles against, and it reports
  `Failed to find package 'platforms;android-37.0'` rather than anything about its own age.
  Ask it to install `cmdline-tools;latest` first, then use the copy it unpacks.
- **That upgrade lands in `cmdline-tools/latest-2`.** The upgraded tools cannot overwrite
  the directory they run from, so the new binaries are one directory along and the `PATH`
  must say so. A warning about an "inconsistent location" is expected and harmless.

## Running a build

Never mount the live checkout read-write. A throwaway build writes `build/`,
`local.properties` and lock files, and it races whatever else is running against the same
tree. Copy first, then build the copy.

```sh
rsync -a --delete \
  --exclude 'build/' --exclude '.gradle/' --exclude '.git' --exclude '.kotlin/' \
  --exclude '.claude/' --exclude 'local.properties' \
  "$SRC/" "$WORK/"

docker run --rm --platform linux/amd64 \
  -v "$WORK:/work" -v "$GRADLE_CACHE:/gradle" \
  -w /work lugu-build:1 \
  ./gradlew build --no-daemon --console=plain
```

Keep `$GRADLE_CACHE` outside both trees and reuse it. The first build downloads the whole
dependency graph and takes several minutes. Later builds take a fraction of that.

**Run one build at a time.** `gradle.properties` asks for a 4 GB heap, the Kotlin daemon
takes its own, and a Docker Desktop VM holds about 8 GB by default. Two builds at once
therefore kill each other, and the message says nothing about memory:

```
FAILURE: Build failed with an exception.
* What went wrong:
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

That failure is the VM out of memory, not a fault in the code.

Lowering the heaps does not fix it. A full `build` runs lint on the release variants and
R8 over the minified one, and those are the two hungriest things this project does — with
the heap cut to 2.5 GB in a 6 GB container it still died, every time, in
`lintVitalAnalyzeRelease`. **Give the VM more memory instead.** Docker Desktop → Settings
→ Resources → Memory; 16 GB is comfortable, and the setting is stored as `MemoryMiB` in
`~/Library/Group Containers/group.com.docker/settings-store.json`. Docker must restart to
take it, which stops every running container.

With that headroom, run one build at a time and let it have the heap the project asks for:

```sh
docker run --rm --platform linux/amd64 --memory=12g --memory-swap=12g \
  -v "$WORK:/work" -v "$GRADLE_CACHE:/gradle" -w /work lugu-build:1 \
  ./gradlew build --no-daemon --console=plain --max-workers=4 \
    -Dorg.gradle.jvmargs="-Xmx4096m -Dfile.encoding=UTF-8" \
    -Dkotlin.daemon.jvmargs="-Xmx1024m"
```

If more memory is not available, build in pieces rather than all at once.
`testDebugUnitTest` proves every unit and screenshot test, and `assembleDebug` plus
`assembleDebugAndroidTest` prove that everything compiles. Together they cover almost all
of what `build` covers, and each of them fits where the whole does not.

`git archive HEAD | tar -x -C "$WORK"` is the alternative to `rsync` when only committed
work needs to be built.

## Recording screenshots

This container is the correct host, and a Mac is not. Record in it, then copy the images
back:

```sh
docker run --rm --platform linux/amd64 \
  -v "$WORK:/work" -v "$GRADLE_CACHE:/gradle" \
  -w /work lugu-build:1 \
  ./gradlew testDebugUnitTest -Proborazzi.record --no-daemon

rsync -a --include '*/' --include 'screenshots/*.png' --exclude '*' "$WORK/" "$SRC/"
```

Then build again against the copied images, to prove they verify rather than assume it.

## What this cannot do

**Instrumented tests need a device, and this is not one.** `connectedDebugAndroidTest`
needs an emulator or a phone on ADB. A container on a Mac has neither, and the emulator
cannot run inside it. The most a container proves about an instrumented test is that it
compiles:

```sh
./gradlew assembleDebugAndroidTest
```

A local AVD can still run them, but it needs the system image its config names — an AVD
that names `system-images/android-36/google_apis/arm64-v8a` does not start from an
`android-26` image, and installing the right one needs `sdkmanager`, which needs a JDK.
On a machine with no JDK the honest answer is that instrumented tests run in CI and
nowhere else.
