# pyxpo-camera

A live camera preview, photos, videos and barcode scanning for [pyxpo](https://github.com/pyxpo/pyxpo) apps, written in
Python. The API follows [expo-camera](https://docs.expo.dev/versions/latest/sdk/camera/). iOS uses AVFoundation (and
Vision to read barcodes in image files). Android uses CameraX and ML Kit barcode scanning with the bundled model, so it
needs no Google Play services.

<p align="center">
  <img src="https://raw.githubusercontent.com/pyxpo/pyxpo-camera/main/docs/images/hero.png" width="820" alt="The camera module on Android and iOS: permission status, availability and a CameraView">
</p>

```python
import pyxpo_camera as Camera

Camera.CameraView(ref=cam, facing="back", style={"flex": 1},
                  barcode_scanner_settings={"barcode_types": ["qr"]},
                  on_barcode_scanned=lambda r: print(r["type"], r["data"]))
```

## Features

- **A `CameraView` component** you size with `style`: back or front camera, flash, torch, zoom, mirror and `active`.
- **Photos** with quality, base64, EXIF and PNG or JPEG output. The image is upright and saved in the app cache.
- **Videos** with a duration or size limit, video quality choice, and an option to mute.
- **Barcode and QR scanning** live in the preview, for 13 types, or from an image file.
- **Permission helpers** for the camera and the microphone, with hooks.
- **Pause and resume** the preview without unmounting.
- **No Google Play services** on Android (CameraX plus the bundled ML Kit model).
- **Typed:** ships `py.typed`. Names also come in expo's camelCase (`takePictureAsync`, `requestCameraPermissionsAsync`).

## Contents

| If you are... | Start here |
| --- | --- |
| Showing a preview and taking a photo | [Quick start](#quick-start) |
| Asking for permission | [Permissions](#permissions) |
| Scanning QR codes or barcodes | [Barcodes](#barcodes) |
| Recording a video | [Video](#video) |
| Every `CameraView` prop | [CameraView](#cameraview) |
| Testing without a camera (iOS Simulator) | [What the screenshots show](#what-the-screenshots-show) |
| Looking for exact signatures | [API reference](#api-reference) · [Errors](#errors) |
| Seeing it in a real app | [Recipe: a ticket scanner](#recipe-a-ticket-scanner) |
| Stuck | [Testing](#testing) · [Troubleshooting](#troubleshooting) · [FAQ](#faq) |

## Platform support

| Target | What works |
| --- | --- |
| Android, API 24+ | Preview, photos, video, barcodes, torch, zoom. Adds `CAMERA` and `RECORD_AUDIO`, and marks the camera optional so the app also installs on devices without one. |
| iOS 15+ | The same. `codabar` needs iOS 15.4. The **Simulator has no camera**: `is_available_async()` is `False` and a `CameraView` calls `on_mount_error`. |
| Web page and desktop window | Not supported. Functions raise `NotSupportedError`. `is_available_async()` returns `False`, and a `CameraView` renders as an empty view. |

Requires Python 3.9+ and `pyxpo>=0.1.0a2`.

## Install

```sh
pip install pyxpo-camera
```

```toml
[tool.pyxpo]
modules = ["camera"]

# Optional: the text of the iOS permission prompts.
[tool.pyxpo.module_options.camera]
cameraPermission = "Scan tickets."
microphonePermission = "Record sound with videos."
```

Rebuild the app after adding the module (`pyxpo run android` or `pyxpo run ios`).

| Option | iOS Info.plist key | Default |
| --- | --- | --- |
| `cameraPermission` | `NSCameraUsageDescription` | "Use the camera." |
| `microphonePermission` | `NSMicrophoneUsageDescription` | "Record sound with videos." |

## Quick start

```python
import pyxpo_camera as Camera
from pyxpo import Button, Text, View, component, use_async, use_ref, use_state


@component
def Snap():
    perm, request, _get = Camera.use_camera_permissions()
    cam = use_ref(None)
    taps, set_taps = use_state(0)
    shot, set_shot = use_state("")

    async def snap():
        if taps == 0 or cam.current is None:
            return
        photo = await cam.current.take_picture_async({"quality": 0.8})
        set_shot("%sx%s" % (photo["width"], photo["height"]))

    use_async(snap, [taps])

    if perm is None:
        return View()
    if not perm["granted"]:
        return Button("Allow the camera", request)
    return View(
        Camera.CameraView(ref=cam, facing="back", style={"flex": 1}),
        Text(shot),
        Button("Take a photo", lambda: set_taps(taps + 1)),
        style={"flex": 1},
    )
```

> **Tip:** `request` from the hook is a coroutine function, so pass it straight to a button, which awaits it. Other
> async work (taking a picture) starts from `use_async` with a counter, because a `lambda` that only builds a coroutine
> is never awaited.

<p align="center">
  <img src="https://raw.githubusercontent.com/pyxpo/pyxpo-camera/main/docs/images/view.png" width="720" alt="The CameraView on Android and the same screen on the iOS Simulator, which has no camera">
</p>

## What the screenshots show

The Android emulator has a virtual camera, and its default scene is dark, so the preview looks black. The iOS Simulator
has no camera at all, so there `is_available_async()` is `False` and the view reports a mount error. Both screens show
the real permission status. Test live preview, photos and scanning on a real phone.

## Permissions

```python
perm = await Camera.get_camera_permissions_async()        # does not ask
perm = await Camera.request_camera_permissions_async()    # asks if undetermined
status, request, get = Camera.use_camera_permissions()    # hook
```

The response is expo's `{"status", "granted", "canAskAgain", "expires"}`. For videos with sound, ask for the microphone
too with `request_microphone_permissions_async()` or `use_microphone_permissions()`. Hooks return `(response, request,
get)` and `response` is `None` until read.

## CameraView

Size it with `style`. It has no children.

| Prop | Notes |
| --- | --- |
| `facing` | `"back"` (default) or `"front"`. |
| `mode` | `"picture"` (default) or `"video"`. `record_async` needs `"video"`. |
| `flash` | `"off"`, `"on"`, `"auto"`: the flash for photos. |
| `enable_torch` | Keeps the light on. |
| `zoom` | 0 (none) to 1 (the most the camera can do, at most 16x on iOS). |
| `mirror` | Mirrors photos and videos of the front camera. The preview is always mirrored. |
| `mute` | Records videos without sound. |
| `active` | `False` stops the camera without unmounting the view. |
| `animate_shutter` | Blinks the preview when a photo is taken (default `True`). |
| `video_quality` | `"2160p"`, `"1080p"`, `"720p"`, `"480p"`, `"4:3"`. The nearest supported one is used. |
| `barcode_scanner_settings` | `{"barcode_types": [...]}`. Default: every type. |
| `on_barcode_scanned(result)` | Called with a scan. See [Barcodes](#barcodes). |
| `on_camera_ready()` | The camera is running. |
| `on_mount_error({"message"})` | The camera cannot start: no permission, or no camera of that `facing`. |

### `ref.current`

Pass `ref=use_ref(None)`. After mount, `ref.current` has:

| Method | Notes |
| --- | --- |
| `take_picture_async(options)` | `{"uri", "width", "height", "format"}`, plus `base64` and `exif` when asked. Options: `quality` (0-1), `base64`, `exif`, `image_type` (`"jpg"` or `"png"`), `mirror`, `shutter_sound` (Android; iOS always plays it). |
| `record_async(options)` | Records until `stop_recording()` or a limit, then returns `{"uri"}` (`.mov` on iOS, `.mp4` on Android). Options: `max_duration` (seconds), `max_file_size` (bytes). |
| `stop_recording()` | Ends the recording. |
| `pause_preview()` / `resume_preview()` | Freezes and restarts the preview. |
| `get_available_picture_sizes_async()` | Sizes such as `"4032x3024"`, largest first. |

## Barcodes

```python
Camera.CameraView(
    ref=cam,
    barcode_scanner_settings={"barcode_types": ["qr", "ean13"]},
    on_barcode_scanned=lambda r: print(r["type"], r["data"]),
)
```

A result is `{"type", "data", "raw", "bounds": {"origin": {"x", "y"}, "size": {"width", "height"}}, "cornerPoints"}`.
Points are in the view (points on iOS, dp on Android). Scanning runs only while `on_barcode_scanned` is set, at most
twice a second.

Types: `aztec`, `ean13`, `ean8`, `qr`, `pdf417`, `upc_e`, `datamatrix`, `code39`, `code93`, `itf14`, `codabar`,
`code128`, `upc_a` (also as `Camera.BarcodeType.QR` and so on). On iOS, `upc_a` codes are reported as `ean13` with a
leading 0.

To read a barcode from an image file:

```python
results = await Camera.scan_from_url_async("file:///path/ticket.png", barcode_types=["qr"])
```

Points are image pixels. On the iOS Simulator it reads only QR codes.

## Video

```python
cam.current.record_async({"max_duration": 10})        # needs mode="video"
...
cam.current.stop_recording()
```

`record_async` returns when recording ends, so run it from `use_async` or start it as a task and call
`stop_recording()` from a button. The camera view needs `mode="video"`. Sound needs the microphone permission unless
`mute=True`.

## Errors

| Error | When | What to do |
| --- | --- | --- |
| `NotSupportedError` | On the web page or desktop window. | Hide the camera UI and offer a file picker. |
| `ModuleNotLinkedError` (from `pyxpo.module_api`) | Built without `"camera"` in `modules`. | Add it and rebuild. |
| `on_mount_error({"message"})` | No permission, no camera of that `facing`, or the iOS Simulator. | Show the message and a way out. Check `is_available_async()` first. |
| A native error from `take_picture_async` | The camera is not ready or the view is gone. | Wait for `on_camera_ready`, and check `cam.current` is not `None`. |

## Config

See [Install](#install) for the iOS prompt texts. On Android, `CAMERA` and `RECORD_AUDIO` are declared by the module.
`RECORD_AUDIO` is used only for video with sound.

## Web and desktop

Not supported. A `CameraView` renders as an empty view, `is_available_async()` returns `False`, and the other functions
raise `NotSupportedError`. Check `await Camera.is_available_async()` and show an alternative, such as
`pyxpo-image-picker`'s file picker.

## Recipe: a ticket scanner

Scan one QR code, then stop the camera and show the result.

```python
import pyxpo_camera as Camera
from pyxpo import Button, Text, View, component, use_state


@component
def Scan():
    perm, request, _get = Camera.use_camera_permissions()
    code, set_code = use_state(None)

    if perm is None:
        return View()
    if not perm["granted"]:
        return View(Text("The camera is needed to scan tickets."), Button("Allow", request))
    if code is not None:
        return View(Text("Ticket %s" % code), Button("Scan again", lambda: set_code(None)))

    return View(
        Camera.CameraView(
            facing="back",
            style={"flex": 1},
            barcode_scanner_settings={"barcode_types": ["qr"]},
            on_barcode_scanned=lambda r: set_code(r["data"]),
            on_mount_error=lambda e: set_code("camera error: %s" % e.get("message")),
        ),
        style={"flex": 1},
    )
```

## Production checklist

- [ ] `cameraPermission` explains what the camera is for.
- [ ] You handle denied permission and `canAskAgain == False` with a way to Settings.
- [ ] You check `is_available_async()` and handle `on_mount_error`.
- [ ] `on_barcode_scanned` is removed (or the view is `active=False`) when you stop scanning, to save battery.
- [ ] You tested photos, video and scanning on a real phone.
- [ ] You delete photos and videos from the cache when done, or move them with `pyxpo-media-library`.
- [ ] The camera screen has a fallback on web and desktop.

## Testing

Unit tests run on the desktop host, where the camera does not exist. Test your own logic around it, and check the
permission helpers with a fake bridge:

```python
import asyncio

import pyxpo_camera as Camera


def test_availability_on_desktop():
    assert asyncio.run(Camera.is_available_async()) is False
```

For component tests, render your screen with the hook replaced:

```python
def test_denied_screen(monkeypatch):
    monkeypatch.setattr(Camera, "use_camera_permissions",
                        lambda: ({"granted": False, "status": "denied"}, lambda: None, lambda: None))
    # render Scan() and assert it shows the "Allow" button
```

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| iOS Simulator: `on_mount_error` and `is_available_async()` is `False` | The Simulator has no camera. Use a real device. |
| Preview is black on the Android emulator | The virtual scene is dark. Change it in Extended controls > Camera, or use a real phone. |
| Nothing is scanned | `on_barcode_scanned` is not set, the type is not in `barcode_types`, or the code is too small. |
| `record_async` fails | The view is not `mode="video"`, or the microphone permission is missing and `mute` is `False`. |
| iOS crashes when the view appears | `NSCameraUsageDescription` is missing. Use the module so it adds a default. |
| `NotSupportedError` | You are on the web or desktop. |
| `ModuleNotLinkedError` | Add `"camera"` to `modules` and rebuild. |

## FAQ

**Does it need Google Play services?** No. ML Kit uses the bundled model.

**Where is the photo saved?** In the app cache. The `uri` is in the result. Use `pyxpo-media-library` to save it to
the gallery.

**Can I record without sound?** Yes, `mute=True`.

**Can I scan several codes at once?** Each scan result is one code, reported at most twice a second.

**Does the preview mirror the front camera?** Always. Photos and videos only when `mirror` is set.

## API reference

### Functions

| Function (camelCase name) | Returns |
| --- | --- |
| `request_camera_permissions_async()` / `get_camera_permissions_async()` | The permission response. |
| `request_microphone_permissions_async()` / `get_microphone_permissions_async()` | The same, for the microphone. |
| `use_camera_permissions()` / `use_microphone_permissions()` | `(response, request, get)` |
| `is_available_async()` | `bool`. `False` on the iOS Simulator and on desktop. |
| `scan_from_url_async(url, barcode_types=["qr"])` | A list of scan results. |

### Components and types

| Name | Description |
| --- | --- |
| `CameraView` | The preview component. See [CameraView](#cameraview). |
| `CameraViewHandle` | The type of `ref.current`. |
| `BarcodeType`, `BARCODE_TYPES` | The 13 barcode types. |
| `PermissionStatus` | `GRANTED`, `DENIED`, `UNDETERMINED`. |
| `NotSupportedError` | Raised on the web and desktop. |

## License

MIT
