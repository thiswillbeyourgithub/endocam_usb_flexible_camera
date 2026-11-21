#!/usr/bin/env bash
# Boots an Android emulator with the endoscope passed through over USB, so the
# Endocam APK can be tested end to end without a phone.
#
# Why sudo: QEMU opens the raw USB node /dev/bus/usb/BBB/DDD read-write, which
# is root-only by default. The chmod is temporary: the node is recreated (with
# default permissions) on the next re-plug.
#
# Usage: scripts/emulator_usb_passthrough.sh [AVD_NAME]
# Expects ANDROID_HOME to point to an SDK with "emulator" installed and an AVD
# (default name: endotest) created with avdmanager. See README.md.
#
# Written with Claude Code.
set -euo pipefail

AVD="${1:-endotest}"
VID=1908
PID=2311
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

line=$(lsusb -d "$VID:$PID" | head -n1)
[ -n "$line" ] || { echo "camera $VID:$PID not plugged in" >&2; exit 1; }
bus=$(awk '{print $2}' <<<"$line")
dev=$(awk '{print substr($4, 1, 3)}' <<<"$line")
node="/dev/bus/usb/$bus/$dev"
echo "camera node: $node"
sudo chmod 666 "$node"

# QEMU's usb-host detaches the host uvcvideo driver while the guest owns the
# camera; it comes back after the emulator exits (or after a re-plug).
exec "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-snapshot -no-audio \
    -qemu -device qemu-xhci -device "usb-host,vendorid=0x$VID,productid=0x$PID"
