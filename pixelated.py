#!/usr/bin/env python3
"""Pixelated - a click-through, always-on-top overlay that pixelates the whole screen.

The overlay continuously captures what is *underneath* it, shrinks the image by the
block size and paints it back up with nearest-neighbour scaling, so everything on
every monitor looks like chunky pixel art while you keep using your computer
normally (mouse and keyboard go straight through to the apps below).

Supported:
  * Windows 10 (2004+) / 11 - the overlay is excluded from screen capture with
    SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE), so it never sees itself.
  * macOS - the screen is captured with CGWindowListCreateImage using
    "everything below this window", so again the overlay never sees itself.
    Requires the Screen Recording permission.
  * Linux - not supported by this app (X11/Wayland cannot hide a window from
    capture). Use the picom shader in linux/ instead, see README.md.

Controls: tray icon menu, plus global hotkeys if `pynput` is installed:
  Ctrl+Alt+P   toggle pixelation on/off
  Ctrl+Alt+T   toggle Space Impact 2-tone look / full colour
  Ctrl+Alt+]   bigger pixels
  Ctrl+Alt+[   smaller pixels
  Ctrl+Alt+Q   quit
"""

from __future__ import annotations

import argparse
import sys

from PIL import Image, ImageStat
from PySide6.QtCore import QObject, QRect, Qt, QTimer, Signal
from PySide6.QtGui import QAction, QActionGroup, QColor, QIcon, QImage, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon, QWidget

BLOCK_SIZES = [4, 6, 8, 12, 16, 24, 32, 48, 64]

# Nokia 3310 LCD palette, as in Space Impact.
NOKIA_DARK = (0x43, 0x52, 0x3D)
NOKIA_LIGHT = (0xC7, 0xF0, 0xD8)


def pixelate(img: Image.Image, block: int, two_tone: bool = False) -> Image.Image:
    """Average every `block` x `block` square of `img` into a single pixel.

    With `two_tone`, every pixel becomes one of the two Nokia LCD tones: blocks darker than the
    frame's average brightness go dark, so text and outlines stay visible and the look adapts
    to light and dark apps.
    """
    small = img.reduce(block) if block > 1 else img
    if not two_tone:
        return small
    luma = small.convert("L")
    threshold = ImageStat.Stat(luma).mean[0]
    mask = luma.point(lambda v: 255 if v < threshold else 0, "1")
    out = Image.new("RGB", small.size, NOKIA_LIGHT)
    out.paste(NOKIA_DARK, mask=mask)
    return out


# --------------------------------------------------------------------------- #
# Platform specific capture backends
# --------------------------------------------------------------------------- #


class WindowsCapture:
    """Grabs the screen with mss; the overlay is hidden from capture by the OS."""

    WDA_EXCLUDEFROMCAPTURE = 0x11

    def __init__(self) -> None:
        import mss

        self._sct = mss.mss()

    def prepare_window(self, widget: QWidget) -> None:
        import ctypes

        hwnd = int(widget.winId())
        ok = ctypes.windll.user32.SetWindowDisplayAffinity(hwnd, self.WDA_EXCLUDEFROMCAPTURE)
        if not ok:
            raise RuntimeError(
                "SetWindowDisplayAffinity failed - Windows 10 version 2004 or newer is required."
            )

    def grab(self, widget: QWidget) -> Image.Image:
        screen = widget.screen()
        geo = screen.geometry()
        dpr = screen.devicePixelRatio()
        # Qt keeps screen origins in native pixels on Windows and scales only the size.
        region = {
            "left": geo.x(),
            "top": geo.y(),
            "width": round(geo.width() * dpr),
            "height": round(geo.height() * dpr),
        }
        shot = self._sct.grab(region)
        return Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")


class MacCapture:
    """Grabs every window *below* the overlay with Quartz."""

    def __init__(self) -> None:
        import Quartz  # pyobjc-framework-Quartz

        self._q = Quartz
        self._window_numbers: dict[int, int] = {}

    def prepare_window(self, widget: QWidget) -> None:
        import objc
        from AppKit import (
            NSScreenSaverWindowLevel,
            NSWindowCollectionBehaviorCanJoinAllSpaces,
            NSWindowCollectionBehaviorFullScreenAuxiliary,
            NSWindowCollectionBehaviorStationary,
        )

        view = objc.objc_object(c_void_p=int(widget.winId()))
        window = view.window()
        window.setLevel_(NSScreenSaverWindowLevel)
        window.setCollectionBehavior_(
            NSWindowCollectionBehaviorCanJoinAllSpaces
            | NSWindowCollectionBehaviorFullScreenAuxiliary
            | NSWindowCollectionBehaviorStationary
        )
        window.setSharingType_(0)  # NSWindowSharingNone: keep it out of other captures too
        self._window_numbers[id(widget)] = window.windowNumber()

    def grab(self, widget: QWidget) -> Image.Image:
        q = self._q
        geo = widget.screen().geometry()
        rect = q.CGRectMake(geo.x(), geo.y(), geo.width(), geo.height())
        cg_image = q.CGWindowListCreateImage(
            rect,
            q.kCGWindowListOptionOnScreenBelowWindow,
            self._window_numbers[id(widget)],
            q.kCGWindowImageDefault,
        )
        if cg_image is None:
            raise RuntimeError(
                "Screen capture failed - grant Screen Recording permission in "
                "System Settings > Privacy & Security, then restart the app."
            )
        width = q.CGImageGetWidth(cg_image)
        height = q.CGImageGetHeight(cg_image)
        stride = q.CGImageGetBytesPerRow(cg_image)
        data = q.CGDataProviderCopyData(q.CGImageGetDataProvider(cg_image))
        return Image.frombuffer("RGB", (width, height), bytes(data), "raw", "BGRX", stride, 1)


def make_capture():
    if sys.platform == "win32":
        return WindowsCapture()
    if sys.platform == "darwin":
        return MacCapture()
    sys.exit(
        "Pixelated cannot hide its own window from screen capture on Linux.\n"
        "Use the picom shader instead: see linux/pixelate.glsl and README.md."
    )


# --------------------------------------------------------------------------- #
# Overlay
# --------------------------------------------------------------------------- #


class Overlay(QWidget):
    """A borderless, click-through window covering one monitor."""

    def __init__(self, screen, state: "State", capture) -> None:
        super().__init__()
        self._state = state
        self._capture = capture
        self._frame: QImage | None = None

        self.setWindowFlags(
            Qt.FramelessWindowHint
            | Qt.WindowStaysOnTopHint
            | Qt.Tool  # no taskbar / dock entry
            | Qt.WindowTransparentForInput  # mouse & keyboard pass straight through
            | Qt.WindowDoesNotAcceptFocus
        )
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setScreen(screen)
        self.setGeometry(screen.geometry())

    def start(self) -> None:
        self.show()
        self._capture.prepare_window(self)

    def refresh(self) -> None:
        if not self._state.enabled:
            if self._frame is not None:
                self._frame = None
                self.update()
            return
        small = pixelate(self._capture.grab(self), self._state.block, self._state.two_tone)
        data = small.tobytes("raw", "RGB")
        self._frame = QImage(data, small.width, small.height, small.width * 3, QImage.Format_RGB888).copy()
        self.update()

    def paintEvent(self, _event) -> None:
        if self._frame is None:
            return
        painter = QPainter(self)
        painter.setRenderHint(QPainter.SmoothPixmapTransform, False)
        # Keep the block grid exact: every source pixel becomes block x block physical pixels.
        scale = self._state.block / self.devicePixelRatioF()
        target = QRect(0, 0, round(self._frame.width() * scale), round(self._frame.height() * scale))
        painter.drawImage(target, self._frame)
        painter.end()


# --------------------------------------------------------------------------- #
# App state, tray and hotkeys
# --------------------------------------------------------------------------- #


class State:
    def __init__(self, block: int, two_tone: bool = True, enabled: bool = True) -> None:
        self.block = block
        self.two_tone = two_tone
        self.enabled = enabled


class Hotkeys(QObject):
    """Global hotkeys via pynput (optional). Signals hop back onto the Qt thread."""

    toggle = Signal()
    tone = Signal()
    bigger = Signal()
    smaller = Signal()
    quit = Signal()

    def start(self) -> bool:
        try:
            from pynput import keyboard
        except ImportError:
            return False
        self._listener = keyboard.GlobalHotKeys(
            {
                "<ctrl>+<alt>+p": self.toggle.emit,
                "<ctrl>+<alt>+t": self.tone.emit,
                "<ctrl>+<alt>+]": self.bigger.emit,
                "<ctrl>+<alt>+[": self.smaller.emit,
                "<ctrl>+<alt>+q": self.quit.emit,
            }
        )
        self._listener.daemon = True
        self._listener.start()
        return True


def tray_icon() -> QIcon:
    pix = QPixmap(32, 32)
    painter = QPainter(pix)
    colors = ["#ff4d6d", "#ffd166", "#06d6a0", "#118ab2"]
    for i, color in enumerate(colors):
        painter.fillRect((i % 2) * 16, (i // 2) * 16, 16, 16, QColor(color))
    painter.end()
    return QIcon(pix)


class PixelatedApp:
    def __init__(self, args: argparse.Namespace) -> None:
        self.qt = QApplication(sys.argv)
        self.qt.setQuitOnLastWindowClosed(False)
        self.state = State(args.block, two_tone=not args.color)
        self.capture = make_capture()

        self.overlays = [Overlay(screen, self.state, self.capture) for screen in self.qt.screens()]
        for overlay in self.overlays:
            overlay.start()

        self._build_tray()

        self.hotkeys = Hotkeys()
        self.hotkeys.toggle.connect(self.toggle)
        self.hotkeys.tone.connect(lambda: self.tone_action.setChecked(not self.state.two_tone))
        self.hotkeys.bigger.connect(lambda: self.step_block(+1))
        self.hotkeys.smaller.connect(lambda: self.step_block(-1))
        self.hotkeys.quit.connect(self.qt.quit)
        if not self.hotkeys.start():
            print("pynput not installed - global hotkeys disabled, use the tray icon.")

        self.timer = QTimer()
        self.timer.timeout.connect(self.tick)
        self.timer.start(max(1, round(1000 / args.fps)))

    def _build_tray(self) -> None:
        self.tray = QSystemTrayIcon(tray_icon())
        self.tray.setToolTip("Pixelated")
        menu = QMenu()

        self.toggle_action = QAction("Pixelate screen", menu)
        self.toggle_action.setCheckable(True)
        self.toggle_action.setChecked(True)
        self.toggle_action.toggled.connect(self.set_enabled)
        menu.addAction(self.toggle_action)

        self.tone_action = QAction("Space Impact style (2 tones)", menu)
        self.tone_action.setCheckable(True)
        self.tone_action.setChecked(self.state.two_tone)
        self.tone_action.toggled.connect(self.set_two_tone)
        menu.addAction(self.tone_action)

        size_menu = menu.addMenu("Pixel size")
        self.size_group = QActionGroup(size_menu)
        self.size_actions = {}
        for size in sorted(set(BLOCK_SIZES + [self.state.block])):
            action = QAction(f"{size} px", size_menu)
            action.setCheckable(True)
            action.setChecked(size == self.state.block)
            action.triggered.connect(lambda _=False, s=size: self.set_block(s))
            self.size_group.addAction(action)
            size_menu.addAction(action)
            self.size_actions[size] = action

        menu.addSeparator()
        menu.addAction("Quit", self.qt.quit)
        self.tray.setContextMenu(menu)
        self.tray.show()
        self._menu = menu

    def tick(self) -> None:
        for overlay in self.overlays:
            overlay.refresh()

    def set_enabled(self, enabled: bool) -> None:
        self.state.enabled = enabled
        self.tick()

    def set_two_tone(self, on: bool) -> None:
        self.state.two_tone = on

    def toggle(self) -> None:
        self.toggle_action.setChecked(not self.state.enabled)

    def set_block(self, size: int) -> None:
        self.state.block = size
        if size in self.size_actions:
            self.size_actions[size].setChecked(True)

    def step_block(self, direction: int) -> None:
        sizes = sorted(self.size_actions)
        index = sizes.index(self.state.block) if self.state.block in sizes else 0
        self.set_block(sizes[max(0, min(len(sizes) - 1, index + direction))])

    def run(self) -> int:
        return self.qt.exec()


def main() -> int:
    parser = argparse.ArgumentParser(description="Pixelate everything on screen.")
    parser.add_argument("-b", "--block", type=int, default=12, help="pixel block size in screen pixels (default 12)")
    parser.add_argument("--fps", type=float, default=30, help="refresh rate (default 30)")
    parser.add_argument("--color", action="store_true", help="full colour instead of the 2-tone Space Impact look")
    args = parser.parse_args()
    if args.block < 2:
        parser.error("--block must be at least 2")
    if args.fps <= 0:
        parser.error("--fps must be positive")
    return PixelatedApp(args).run()


if __name__ == "__main__":
    sys.exit(main())
