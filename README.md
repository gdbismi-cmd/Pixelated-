# Pixelated

An always-on-top, click-through overlay that pixelates **everything on your screen**, live.
Your apps keep working as normal underneath: clicks and keystrokes go straight through the overlay.


## How it works

Each monitor gets a borderless, transparent window that never takes focus. About 30 times a second it
captures the screen *underneath itself*, averages every N×N block into one pixel, and draws the result
back at full size with nearest-neighbour scaling.

The overlay stays out of its own capture, which is what lets it see the screen beneath it:

| Platform | How the overlay stays out of the capture |
|---|---|
| Windows 10 (2004+) / 11 | `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` |
| macOS | `CGWindowListCreateImage(... OnScreenBelowWindow ...)` |
| Linux | Not possible from an app, so use the picom shader (below) |

## Install & run (Windows / macOS)

Requires Python 3.9+.

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate     macOS: source .venv/bin/activate
pip install -r requirements.txt
python pixelated.py                 # default 12 px blocks, 30 fps
python pixelated.py --block 24      # chunkier
python pixelated.py --fps 60        # smoother (more CPU)
```

**macOS:** the first time you run it, grant *Screen Recording* permission to your terminal (or Python)
under System Settings → Privacy & Security, then start the app again. Global hotkeys also need the
*Accessibility* permission.

## Controls

A tray / menu-bar icon (four coloured squares) gives you:
- **Pixelate screen**: turn the effect on or off
- **Pixel size**: 4 to 64 px
- **Quit**

Global hotkeys (when `pynput` is installed):

| Keys | Action |
|---|---|
| `Ctrl+Alt+P` | Toggle pixelation |
| `Ctrl+Alt+]` | Bigger pixels |
| `Ctrl+Alt+[` | Smaller pixels |
| `Ctrl+Alt+Q` | Quit |

## Linux

X11 and Wayland give an app no way to hide its own window from a screen capture, so on Linux the
pixelation runs inside the compositor. With [picom](https://github.com/yshui/picom) v10 or newer:

```bash
picom --backend glx --window-shader-fg "$PWD/linux/pixelate.glsl"
```

Change `BLOCK` at the top of `linux/pixelate.glsl` to set the pixel size.

## Build a standalone executable (optional)

```bash
pip install pyinstaller
pyinstaller --onefile --windowed --name Pixelated pixelated.py
```
