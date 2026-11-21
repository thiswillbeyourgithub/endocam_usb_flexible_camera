"""Hardware-free tests for endocam.py.

Run with:
    uv run --with pytest --with click --with loguru --with numpy \
        --with opencv-python --with linuxpy pytest tests
"""

import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import endocam  # noqa: E402


def _fake_sysfs(root: Path, vid: str = "1908", pid: str = "2311") -> Path:
    """Build a minimal sysfs tree mimicking a plugged UVC camera.

    Layout (symlinks as in real sysfs):
        class/video4linux/video0 -> devices/usb1/1-3/1-3:1.0/video4linux/video0 (index 0)
        class/video4linux/video1 -> ...                                 video1 (index 1, metadata)
        class/input/event16      -> devices/usb1/1-3/1-3:1.0/input/input88/event16
    """
    usb = root / "devices/usb1/1-3"
    intf = usb / "1-3:1.0"
    usb.mkdir(parents=True)
    (usb / "idVendor").write_text(vid + "\n")
    (usb / "idProduct").write_text(pid + "\n")
    for i in (1, 0):
        node = intf / "video4linux" / f"video{i}"
        node.mkdir(parents=True)
        (node / "index").write_text(f"{i}\n")
        link = root / "class/video4linux" / f"video{i}"
        link.parent.mkdir(parents=True, exist_ok=True)
        link.symlink_to(node)
    ev = intf / "input/input88/event16"
    ev.mkdir(parents=True)
    (root / "class/input").mkdir(parents=True)
    (root / "class/input/event16").symlink_to(ev)
    return root


def test_find_camera_picks_capture_node_and_button(tmp_path: Path) -> None:
    cam = endocam.find_camera(sysfs=_fake_sysfs(tmp_path))
    # video0 has index 0 (capture); video1 is the metadata node.
    assert cam.video == "/dev/video0"
    assert cam.button == "/dev/input/event16"


def test_find_camera_wrong_id_raises(tmp_path: Path) -> None:
    with pytest.raises(endocam.click.ClickException):
        endocam.find_camera(vid="dead", pid="beef", sysfs=_fake_sysfs(tmp_path))


def test_recorder_pads_to_real_time(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A camera delivering 10 fps recorded at 30 fps for 1 s must give ~30 frames.

    Regression test: before padding, 3 s of 10 fps footage became a 1 s video.
    """
    clock = {"t": 100.0}
    monkeypatch.setattr(endocam.time, "monotonic", lambda: clock["t"])
    rec = endocam.Recorder(outdir=tmp_path, fps=30.0)
    rec.start((64, 48))
    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    for _ in range(10):  # 10 frames spread over 1 second
        rec.write(frame)
        clock["t"] += 0.1
    rec.stop()
    # clock is at 101.0 when stop() pads up to the stop time: exactly 30 frames.
    assert rec.written == 30
    cap = cv2.VideoCapture(str(rec.path))
    assert abs(cap.get(cv2.CAP_PROP_FRAME_COUNT) - rec.written) <= 1


def test_timestamped_creates_dir(tmp_path: Path) -> None:
    p = endocam.timestamped(tmp_path / "sub", "snap", "png")
    assert p.parent.is_dir() and p.name.startswith("snap_") and p.suffix == ".png"
