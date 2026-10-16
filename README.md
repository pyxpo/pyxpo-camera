# pyxpo-camera

A camera preview, photos, videos and barcode scanning for [pyxpo](https://github.com/pyxpo/pyxpo) apps. The API
follows [expo-camera](https://docs.expo.dev/versions/latest/sdk/camera/). iOS uses AVFoundation, plus Vision to scan
image files. Android uses CameraX and ML Kit barcode scanning (the bundled model, no Google Play services).

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

```python
import pyxpo_camera as Camera
from pyxpo import Button, Text, View, component, use_ref

@component
def Scanner():
    perm, request, _ = Camera.use_camera_permissions()
    cam = use_ref(None)
    if perm is None:
        return View()
    if not perm["granted"]:
        return Button(title="Allow the camera", on_press=request)

    async def snap():
        photo = await cam.current.take_picture_async({"quality": 0.8})
        print(photo["uri"], photo["width"], photo["height"])

    return View(
        Camera.CameraView(ref=cam, facing="back", style={"flex": 1},
                          barcode_scanner_settings={"barcode_types": ["qr"]},
                          on_barcode_scanned=lambda r: print(r["type"], r["data"])),
        Button(title="Take a photo", on_press=snap),
        style={"flex": 1},
    )
```

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
| `active` | False stops the camera without unmounting the view. |
| `animate_shutter` | Blinks the preview when a photo is taken (default True). |
| `video_quality` | `"2160p"`, `"1080p"`, `"720p"`, `"480p"`, `"4:3"`. The nearest supported one is used. |
| `barcode_scanner_settings` | `{"barcode_types": [...]}`. Default: every type. |
| `on_barcode_scanned(result)` | `{"type", "data", "raw", "bounds": {"origin": {"x", "y"}, "size": {"width", "height"}}, "cornerPoints"}`. Points are in the view (pt / dp). Scanning runs only while this prop is set, at most twice a second. |
| `on_camera_ready()` | The camera is running. |
| `on_mount_error({"message"})` | The camera cannot start: no permission, no camera of that `facing`. |

Barcode types: `aztec`, `ean13`, `ean8`, `qr`, `pdf417`, `upc_e`, `datamatrix`, `code39`, `code93`, `itf14`,
`codabar`, `code128`, `upc_a` (`BarcodeType.QR`, ...). On iOS, `upc_a` codes are reported as `ean13` with a leading 0,
and `codabar` needs iOS 15.4.

## ref.current

| Method | Notes |
| --- | --- |
| `take_picture_async(options)` | `{"uri", "width", "height", "format"}`, plus `base64` and `exif` when asked. Options: `quality` (0-1), `base64`, `exif`, `image_type` (`"jpg"` or `"png"`), `mirror`, `shutter_sound` (Android; iOS always plays it). The image is upright; the file is in the app cache. |
| `record_async(options)` | Records until `stop_recording()` or a limit, then returns `{"uri"}` (`.mov` on iOS, `.mp4` on Android). Options: `max_duration` (seconds), `max_file_size` (bytes). Sound needs the microphone permission unless `mute=True`. |
| `stop_recording()` | Ends the recording. |
| `pause_preview()` / `resume_preview()` | Freezes and restarts the preview. |
| `get_available_picture_sizes_async()` | Sizes like `"4032x3024"`, largest first. |

## Functions

| Function | Notes |
| --- | --- |
| `request_camera_permissions_async()` / `get_camera_permissions_async()` | Expo's permission response. |
| `request_microphone_permissions_async()` / `get_microphone_permissions_async()` | For videos with sound. |
| `use_camera_permissions()` / `use_microphone_permissions()` | Hooks: `(response, request, get)`. |
| `is_available_async()` | Whether the device has a camera. |
| `scan_from_url_async(url, barcode_types=["qr"])` | Barcodes in an image file (`file://` URI or path). Points are image pixels. |

camelCase names work too (`takePictureAsync`, `Camera.requestCameraPermissionsAsync`).

## Platforms

iOS 15+ and Android 7+ (API 24). The iOS Simulator has no camera: `is_available_async()` returns False and a
`CameraView` calls `on_mount_error`; `scan_from_url_async` reads only QR codes there (Vision finds no barcodes in the Simulator). On desktop the functions raise
`NotSupportedError`, except `is_available_async()`, which returns False, and a `CameraView` renders as an empty view.

Android adds the `CAMERA` and `RECORD_AUDIO` permissions to the app, and marks the camera as optional, so the
app also installs on devices without one.
