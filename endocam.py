# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "click",
#     "loguru",
#     "numpy",
#     "opencv-python",
#     "linuxpy",
# ]
# ///
"""endocam: proof of concept tool for a USB endoscope camera (GEMBIRD / AppoTech AX2311, 1908:2311).

The camera is a plain UVC device, so on Linux it shows up as:

- a V4L2 video node (``/dev/videoN``) streaming YUYV at up to 640x480,
- a V4L2 control set (brightness, contrast, saturation, hue, gamma, sharpness,
  backlight compensation, power line frequency),
- an evdev input node (``/dev/input/eventN``) created by the ``uvcvideo``
  driver for the snapshot button, emitting ``KEY_CAMERA`` (212).

The LED wheel next to the button is purely analog and is not visible over USB.

Subcommands (run ``uv run endocam.py --help``):

- ``info``     : print device paths, formats and controls.
- ``preview``  : live window with control sliders; the hardware button takes a
                 snapshot (or toggles recording with ``--button-action record``).
- ``snap``     : save a single image.
- ``record``   : record a video for a duration, or until the button is pressed.
- ``controls`` : read or set V4L2 controls (``--set brightness=150``).
- ``button``   : print button press/release events.

Device discovery is done through sysfs by USB vendor/product id, so the tool
keeps working when the ``/dev/videoN`` number changes between plug-ins.

Written with Claude Code.
"""

from __future__ import annotations

import os
import struct
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable

import click
import cv2
import numpy as np
from linuxpy.video.device import Device, MenuControl
from loguru import logger

# USB ids reported by lsusb for the endoscope.
DEFAULT_VID = "1908"
DEFAULT_PID = "2311"

# struct input_event on 64 bit Linux: timeval (2 x long), type (u16), code (u16), value (s32).
INPUT_EVENT = struct.Struct("llHHi")
EV_KEY = 1
KEY_CAMERA = 212


@dataclass
class CameraPaths:
    """Device nodes belonging to one physical camera.

    Attributes
    ----------
    video : str
        Path of the V4L2 capture node, e.g. ``/dev/video0``.
    button : str | None
        Path of the evdev node of the snapshot button, or None if the driver
        did not expose one.
    usb_sysfs : str
        Resolved sysfs path of the USB device, used to match nodes together.
    """

    video: str
    button: str | None
    usb_sysfs: str


def _usb_device_dir(path: Path) -> Path | None:
    """Walk up a sysfs path until reaching the USB device (the dir holding idVendor).

    Parameters
    ----------
    path : Path
        Any resolved sysfs path below a USB device.

    Returns
    -------
    Path | None
        The USB device directory, or None if ``path`` is not below a USB device.
    """
    for parent in [path, *path.parents]:
        if (parent / "idVendor").exists():
            return parent
    return None


def find_camera(vid: str = DEFAULT_VID, pid: str = DEFAULT_PID, sysfs: Path = Path("/sys")) -> CameraPaths:
    """Locate the video and button nodes of the camera via sysfs.

    A UVC camera exposes several video nodes (one capture node and one
    metadata node). The capture node is the one with the lowest ``index``
    attribute, which is what we pick.

    Parameters
    ----------
    vid, pid : str
        USB vendor and product id in lowercase hex.
    sysfs : Path
        Root of sysfs. Only overridden by tests, which build a fake tree.

    Returns
    -------
    CameraPaths
        The matched device nodes.

    Raises
    ------
    click.ClickException
        If no matching video node exists.
    """
    candidates: list[tuple[int, str, Path]] = []
    for node in sorted((sysfs / "class/video4linux").glob("video*")):
        usb = _usb_device_dir(node.resolve())
        if usb is None:
            continue
        if (usb / "idVendor").read_text().strip() != vid or (usb / "idProduct").read_text().strip() != pid:
            continue
        index = int((node / "index").read_text().strip()) if (node / "index").exists() else 0
        candidates.append((index, f"/dev/{node.name}", usb))
    if not candidates:
        raise click.ClickException(f"No camera with USB id {vid}:{pid} found. Is it plugged in?")
    _, video, usb = min(candidates)

    button = None
    for ev in sorted((sysfs / "class/input").glob("event*")):
        if _usb_device_dir(ev.resolve()) == usb:
            button = f"/dev/input/{ev.name}"
            break
    return CameraPaths(video=video, button=button, usb_sysfs=str(usb))


class ButtonWatcher:
    """Read the camera button in a background thread and call a callback on press.

    Raw ``os.read`` on the evdev node is used instead of a library because the
    protocol is a fixed-size struct and this keeps dependencies minimal.

    Parameters
    ----------
    path : str
        evdev node of the button.
    on_press : Callable[[], None]
        Called (from the watcher thread) on each key press (value 1).
    on_event : Callable[[int, int], None] | None
        Optional, called with (code, value) for every key event, including
        releases (0) and autorepeat (2).
    """

    def __init__(
        self,
        path: str,
        on_press: Callable[[], None],
        on_event: Callable[[int, int], None] | None = None,
    ) -> None:
        self.path = path
        self.on_press = on_press
        self.on_event = on_event
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> "ButtonWatcher":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        try:
            fd = os.open(self.path, os.O_RDONLY)
        except PermissionError:
            logger.warning(f"No permission to read {self.path}; add yourself to the 'input' group to use the button.")
            return
        try:
            while not self._stop.is_set():
                data = os.read(fd, INPUT_EVENT.size)
                if len(data) < INPUT_EVENT.size:
                    continue
                _, _, ev_type, code, value = INPUT_EVENT.unpack(data)
                if ev_type != EV_KEY:
                    continue
                if self.on_event:
                    self.on_event(code, value)
                if value == 1:
                    self.on_press()
        except OSError as e:
            # Happens when the camera is unplugged while we read.
            logger.warning(f"Button reader stopped: {e}")
        finally:
            os.close(fd)


def open_capture(video: str, width: int, height: int) -> cv2.VideoCapture:
    """Open the V4L2 node with OpenCV and request a resolution.

    Parameters
    ----------
    video : str
        Path of the capture node.
    width, height : int
        Requested frame size; the camera supports 640x480, 352x288, 320x240,
        176x144 and 160x120 (YUYV only).

    Returns
    -------
    cv2.VideoCapture
        An opened capture object.
    """
    cap = cv2.VideoCapture(video, cv2.CAP_V4L2)
    if not cap.isOpened():
        raise click.ClickException(f"Cannot open {video}")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    real = (int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)))
    logger.debug(f"Opened {video} at {real[0]}x{real[1]}")
    return cap


def read_frame(cap: cv2.VideoCapture) -> np.ndarray:
    """Read one BGR frame, raising a readable error if the stream died."""
    ok, frame = cap.read()
    if not ok:
        raise click.ClickException("Failed to read a frame (camera unplugged?)")
    return frame


def timestamped(outdir: Path, prefix: str, ext: str) -> Path:
    """Build ``outdir/prefix_YYYYmmdd_HHMMSS_ms.ext`` and make sure outdir exists."""
    outdir.mkdir(parents=True, exist_ok=True)
    return outdir / f"{prefix}_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')[:-3]}.{ext}"


class Recorder:
    """Thin wrapper around cv2.VideoWriter that can be toggled on and off.

    The camera has no fixed frame rate: in low light it lengthens exposure and
    drops to ~10 fps, and turning the LED wheel changes it again. A writer
    with a fixed fps would then play back too fast. To keep real-time
    playback, ``write`` pads with repeated frames so that the number of
    written frames always tracks ``elapsed_seconds * fps``. Example at
    fps=30 with a camera giving 10 fps: each captured frame is written ~3
    times.
    """

    def __init__(self, outdir: Path, fps: float) -> None:
        self.outdir = outdir
        self.fps = fps
        self.writer: cv2.VideoWriter | None = None
        self.path: Path | None = None
        self.t0 = 0.0
        self.written = 0
        self.last: np.ndarray | None = None

    @property
    def active(self) -> bool:
        return self.writer is not None

    def start(self, frame_size: tuple[int, int]) -> None:
        self.path = timestamped(self.outdir, "video", "mp4")
        self.writer = cv2.VideoWriter(str(self.path), cv2.VideoWriter_fourcc(*"mp4v"), self.fps, frame_size)
        self.t0 = time.monotonic()
        self.written = 0
        self.last = None
        logger.info(f"Recording to {self.path}")

    def write(self, frame: np.ndarray) -> None:
        if self.writer is None:
            return
        self.last = frame
        # Frame i covers [i/fps, (i+1)/fps); the frame arriving at `elapsed`
        # must fill every slot up to and including the current one. The 1e-6
        # absorbs float error (0.9 * 30 = 26.999999 would lose a slot). At
        # least one copy is written so no captured frame is ever skipped.
        elapsed = time.monotonic() - self.t0
        self._pad(max(int(elapsed * self.fps + 1e-6) + 1, self.written + 1))

    def _pad(self, target: int) -> None:
        """Write ``self.last`` until ``target`` frames have been written."""
        while self.written < target:
            self.writer.write(self.last)
            self.written += 1

    def stop(self) -> None:
        if self.writer is not None:
            # Hold the last frame until the stop time, so the file lasts
            # exactly as long as the recording did.
            if self.last is not None:
                self._pad(round((time.monotonic() - self.t0) * self.fps))
            self.writer.release()
            logger.info(f"Saved video {self.path}")
        self.writer = None


def list_controls(video: str) -> list:
    """Return the linuxpy control objects of the device (integer and menu controls)."""
    with Device(video) as dev:
        return list(dev.controls.values())


def set_control(video: str, name: str, value: int) -> None:
    """Set one V4L2 control by name, e.g. ``set_control('/dev/video0', 'brightness', 150)``."""
    with Device(video) as dev:
        dev.controls[name].value = value


@click.group()
@click.option("--vid", default=DEFAULT_VID, show_default=True, help="USB vendor id (hex).")
@click.option("--pid", default=DEFAULT_PID, show_default=True, help="USB product id (hex).")
@click.option("--device", default=None, help="Force a video node (e.g. /dev/video2), skipping USB id discovery.")
@click.pass_context
def cli(ctx: click.Context, vid: str, pid: str, device: str | None) -> None:
    """Interact with a USB UVC endoscope camera."""
    if device:
        ctx.obj = CameraPaths(video=device, button=None, usb_sysfs="")
    else:
        ctx.obj = find_camera(vid=vid, pid=pid)


@cli.command()
@click.pass_obj
def info(cam: CameraPaths) -> None:
    """Print device nodes, supported formats and controls."""
    with Device(cam.video) as dev:
        click.echo(f"card    : {dev.info.card}")
        click.echo(f"driver  : {dev.info.driver}")
        click.echo(f"video   : {cam.video}")
        click.echo(f"button  : {cam.button}")
        click.echo(f"usb     : {cam.usb_sysfs}")
        click.echo("formats :")
        for fs in dev.info.frame_sizes():
            click.echo(f"  {fs.pixel_format.name} {fs.info.width}x{fs.info.height}")
        click.echo("controls:")
        for c in dev.controls.values():
            click.echo(f"  {c!r}")


@cli.command()
@click.option("--set", "assignments", multiple=True, help="name=value, repeatable. Without it, controls are listed.")
@click.option("--reset", is_flag=True, help="Reset every control to its default value.")
@click.pass_obj
def controls(cam: CameraPaths, assignments: tuple[str, ...], reset: bool) -> None:
    """Read or set image controls (brightness, contrast, saturation, hue, ...)."""
    with Device(cam.video) as dev:
        if reset:
            for c in dev.controls.values():
                c.value = c.default
        for a in assignments:
            name, _, value = a.partition("=")
            dev.controls[name.strip()].value = int(value)
        for c in dev.controls.values():
            click.echo(f"{c.config_name:<24} {c.value}")


@cli.command()
@click.pass_obj
def button(cam: CameraPaths) -> None:
    """Print button events until Ctrl-C."""
    if not cam.button:
        raise click.ClickException("No button input node found for this camera.")
    names = {0: "release", 1: "press", 2: "repeat"}
    click.echo(f"Listening on {cam.button}, press the camera button (Ctrl-C to quit)")
    w = ButtonWatcher(
        path=cam.button,
        on_press=lambda: None,
        on_event=lambda code, value: click.echo(f"{datetime.now():%H:%M:%S.%f} code={code} {names.get(value, value)}"),
    ).start()
    try:
        while w._thread.is_alive():
            time.sleep(0.2)
    except KeyboardInterrupt:
        pass


@cli.command()
@click.option("--out", "outdir", type=click.Path(path_type=Path), default=Path("captures"), show_default=True)
@click.option("--width", default=640, show_default=True)
@click.option("--height", default=480, show_default=True)
@click.option("--warmup", default=10, show_default=True, help="Frames dropped first so auto exposure settles.")
@click.pass_obj
def snap(cam: CameraPaths, outdir: Path, width: int, height: int, warmup: int) -> None:
    """Save one image."""
    cap = open_capture(cam.video, width, height)
    for _ in range(warmup):
        read_frame(cap)
    path = timestamped(outdir, "snap", "png")
    cv2.imwrite(str(path), read_frame(cap))
    cap.release()
    click.echo(path)


@cli.command()
@click.option("--out", "outdir", type=click.Path(path_type=Path), default=Path("captures"), show_default=True)
@click.option("--width", default=640, show_default=True)
@click.option("--height", default=480, show_default=True)
@click.option("--seconds", default=0.0, show_default=True, help="Duration; 0 means until the button or Ctrl-C.")
@click.option("--fps", default=30.0, show_default=True, help="Frame rate of the output file (frames are repeated to keep real-time speed).")
@click.pass_obj
def record(cam: CameraPaths, outdir: Path, width: int, height: int, seconds: float, fps: float) -> None:
    """Record a video without a window (headless)."""
    cap = open_capture(cam.video, width, height)
    stop = threading.Event()
    if cam.button:
        ButtonWatcher(path=cam.button, on_press=stop.set).start()
    frame = read_frame(cap)
    rec = Recorder(outdir, fps)
    rec.start((frame.shape[1], frame.shape[0]))
    t0 = time.monotonic()
    try:
        while not stop.is_set() and (seconds <= 0 or time.monotonic() - t0 < seconds):
            rec.write(read_frame(cap))
    except KeyboardInterrupt:
        pass
    finally:
        rec.stop()
        cap.release()


@cli.command()
@click.option("--out", "outdir", type=click.Path(path_type=Path), default=Path("captures"), show_default=True)
@click.option("--width", default=640, show_default=True)
@click.option("--height", default=480, show_default=True)
@click.option("--fps", default=30.0, show_default=True, help="Frame rate of the output file (frames are repeated to keep real-time speed).")
@click.option(
    "--button-action",
    type=click.Choice(["snap", "record"]),
    default="snap",
    show_default=True,
    help="What the hardware button does.",
)
@click.pass_obj
def preview(cam: CameraPaths, outdir: Path, width: int, height: int, fps: float, button_action: str) -> None:
    """Live window. Keys: s=snapshot r=record f=flip m=mirror d=defaults q/Esc=quit.

    Each integer V4L2 control gets a slider in a separate 'controls' window.
    Sliders start at 0 in OpenCV, so controls with a negative minimum (hue)
    are shown offset by their minimum.
    """
    cap = open_capture(cam.video, width, height)
    rec = Recorder(outdir, fps)
    # The button thread only sets flags; all OpenCV work stays in the main
    # thread because HighGUI is not thread safe.
    pressed = threading.Event()
    if cam.button:
        ButtonWatcher(path=cam.button, on_press=pressed.set).start()
    else:
        logger.warning("No button node, hardware button disabled")

    win, ctl_win = "endocam", "controls"
    cv2.namedWindow(win, cv2.WINDOW_NORMAL)
    cv2.namedWindow(ctl_win, cv2.WINDOW_NORMAL)
    dev = Device(cam.video)
    dev.open()
    sliders = [c for c in dev.controls.values() if not isinstance(c, MenuControl)]

    def make_cb(ctrl) -> Callable[[int], None]:
        def cb(pos: int) -> None:
            ctrl.value = pos + ctrl.minimum

        return cb

    for c in sliders:
        cv2.createTrackbar(c.config_name, ctl_win, c.value - c.minimum, c.maximum - c.minimum, make_cb(c))

    flip_v = mirror = False
    flash_until = 0.0
    t_prev, fps_est = time.monotonic(), 0.0
    try:
        while True:
            frame = read_frame(cap)
            if flip_v:
                frame = cv2.flip(frame, 0)
            if mirror:
                frame = cv2.flip(frame, 1)

            key = cv2.waitKey(1) & 0xFF
            do_snap = key == ord("s")
            do_rec = key == ord("r")
            if pressed.is_set():
                pressed.clear()
                do_snap |= button_action == "snap"
                do_rec |= button_action == "record"

            if do_snap:
                path = timestamped(outdir, "snap", "png")
                cv2.imwrite(str(path), frame)
                logger.info(f"Saved {path}")
                flash_until = time.monotonic() + 0.3
            if do_rec:
                if rec.active:
                    rec.stop()
                else:
                    rec.start((frame.shape[1], frame.shape[0]))
            rec.write(frame)

            if key in (ord("q"), 27):
                break
            if key == ord("f"):
                flip_v = not flip_v
            if key == ord("m"):
                mirror = not mirror
            if key == ord("d"):
                for c in sliders:
                    c.value = c.default
                    cv2.setTrackbarPos(c.config_name, ctl_win, c.default - c.minimum)

            # Overlay is drawn on a copy so snapshots and videos stay clean.
            now = time.monotonic()
            fps_est = 0.9 * fps_est + 0.1 / max(now - t_prev, 1e-3)
            t_prev = now
            shown = frame.copy()
            cv2.putText(shown, f"{fps_est:4.1f} fps", (8, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
            if rec.active:
                cv2.circle(shown, (shown.shape[1] - 16, 16), 7, (0, 0, 255), -1)
            if now < flash_until:
                cv2.rectangle(shown, (0, 0), (shown.shape[1] - 1, shown.shape[0] - 1), (255, 255, 255), 6)
            cv2.imshow(win, shown)
            if cv2.getWindowProperty(win, cv2.WND_PROP_VISIBLE) < 1:
                break
    finally:
        rec.stop()
        cap.release()
        dev.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    cli()
