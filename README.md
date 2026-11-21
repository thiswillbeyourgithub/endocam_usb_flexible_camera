# endocam

Tools for cheap USB endoscope cameras (the "camera at the end of a flexible tube" kind), developed against a GEMBIRD unit based on the AppoTech AX2311 chip (`lsusb`: `1908:2311 GEMBIRD Generic UVC 1.00 camera`).

The camera is a standard UVC device, so this should also work with most other USB endoscopes and webcams.

It contains two independent pieces:

- `endocam.py`: a single-file Python tool for Linux, run with `uv run`.
- `android/`: a minimal Android app (APK) to use the camera from a phone over USB-C (or an OTG adapter).

This project was written with [Claude Code](https://claude.com/claude-code).

## What the camera exposes

Findings from probing the device on Linux:

| feature | how it shows up |
| --- | --- |
| video | YUYV 4:2:2 only, 640x480, 352x288, 320x240, 176x144, 160x120. Up to 30 fps, drops to about 10 fps in low light because exposure gets longer. |
| image controls | brightness (0-255), contrast (0-255), saturation (0-255), hue (-127..127), gamma (1-8), sharpness (0-15), backlight compensation (1-5), power line frequency (menu). |
| snapshot button | the UVC status interrupt endpoint. On Linux the `uvcvideo` driver turns it into an input device sending `KEY_CAMERA` (code 212), e.g. `/dev/input/by-id/usb-Generic_USB2.0_PC_CAMERA-event-if00`. |
| LED wheel | purely analog, not visible or controllable over USB. |

## Python tool (Linux)

Requires [uv](https://docs.astral.sh/uv/). Dependencies are declared inline (PEP 723), so there is nothing to install:

```sh
uv run endocam.py info                 # device nodes, formats, controls
uv run endocam.py preview              # live window with control sliders
uv run endocam.py snap                 # save one PNG into ./captures
uv run endocam.py record --seconds 10  # headless MP4 recording
uv run endocam.py record               # record until the camera button (or Ctrl-C)
uv run endocam.py controls --set brightness=150 --set saturation=120
uv run endocam.py controls --reset
uv run endocam.py button               # print button press/release events
```

Preview keys: `s` snapshot, `r` start/stop recording, `f` flip vertically, `m` mirror, `d` reset controls to defaults, `q`/`Esc` quit. The hardware button takes a snapshot by default, use `preview --button-action record` to make it toggle recording instead.

The camera is found by USB id through sysfs, so the `/dev/videoN` number does not matter. Use `--vid/--pid` for another camera or `--device /dev/videoN` to force a node.

Reading the button needs read access to the `/dev/input/eventN` node, which normally means being in the `input` group.

Recordings stay real-time even when the camera frame rate drops: frames are repeated so that the file duration matches the wall clock.

Tests (no hardware needed):

```sh
uv run --with pytest --with click --with loguru --with numpy --with opencv-python --with linuxpy pytest tests
```

## Android app

A small, dependency-free app (plain Java + NDK). It talks to the camera directly over USB through vendored [libusb](https://github.com/libusb/libusb) and [libuvc](https://github.com/libuvc/libuvc), so it needs no root and does not depend on the phone's camera service supporting external cameras.

Features:

- live preview, rotate in 90 degree steps, mirror,
- photo (JPEG to `Pictures/Endocam`) and video (H.264 MP4 to `Movies/Endocam`), no storage permission needed,
- the camera's hardware button takes a photo or starts/stops a video (choice in the settings panel),
- volume down takes a photo, volume up starts/stops a video,
- sliders for every image control the camera supports, remembered across re-plugs,
- resolution choice, and the app offers to open itself when the camera is plugged in.

Requires Android 10 (API 29) or newer and a phone with USB host (OTG) support, which nearly all recent phones have.

### Install

Build it (see below) and install `android/app/build/outputs/apk/release/app-release.apk`, either with `adb install app-release.apk` or by copying the file to the phone and opening it (allow "install unknown apps" for your file manager). The release APK is signed with the Android debug key so it can be sideloaded directly.

Plug in the camera, accept the USB permission prompt (tick "always open" to skip it next time), and the image should appear.

### Build

You need a JDK 17+ and the Android SDK with platform 35, build-tools, NDK 27.2.12479018 and CMake 3.22.1. With only the command line tools:

```sh
export ANDROID_HOME=$HOME/android_sdk
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;27.2.12479018" "cmake;3.22.1"
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
```

### How it works

- `MainActivity.java` finds the camera with `UsbManager`, asks for permission, opens it and force-claims its interfaces (which detaches the kernel UVC driver on phones that have one), then hands the file descriptor to native code.
- `endocam_jni.c` wraps that fd with `uvc_wrap()` (libusb runs with `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`, since apps cannot enumerate `/dev/bus/usb`). YUYV frames are converted to RGBA directly into the preview surface. The button arrives as a UVC status packet and is forwarded to Java.
- `VideoRecorder.java` encodes with `MediaCodec` (Image API, so it adapts to the encoder's YUV layout) and stamps real capture times, so videos play at real speed despite the variable frame rate.

### Testing without a phone

`scripts/emulator_usb_passthrough.sh` boots an Android emulator with the camera passed through over USB. It needs `sudo` once, to make the camera's raw USB node writable for QEMU.

## Status

This is a proof of concept. The Python tool was tested with the real camera (info, snapshot, recording, controls, preview window). The Android app builds and runs in an emulator (UI, rotation, settings panel), but the USB camera path has not yet been tested on a real phone.

## License

TODO: choose a license. Note that the vendored libusb is LGPL-2.1 (statically linked into the app, which the full source distribution here satisfies) and libuvc is BSD-3-Clause, see `android/app/src/main/cpp/third_party/`.

Repository URL: TODO
