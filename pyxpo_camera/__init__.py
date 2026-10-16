"""Camera preview, photos, videos and barcode scanning: the expo-camera API.

    import pyxpo_camera as Camera
    from pyxpo import use_ref

    perm, request, _ = Camera.use_camera_permissions()
    cam = use_ref(None)

    Camera.CameraView(ref=cam, facing="back", style={"flex": 1},
                      barcode_scanner_settings={"barcode_types": ["qr"]},
                      on_barcode_scanned=lambda r: print(r["type"], r["data"]))

    photo = await cam.current.take_picture_async({"quality": 0.8})
    photo["uri"], photo["width"], photo["height"]

iOS uses AVFoundation (barcodes with AVCaptureMetadataOutput, images with Vision). Android uses CameraX and
ML Kit (bundled model, no Google Play services). The iOS Simulator has no camera: `is_available_async()` is
False there and a `CameraView` calls `on_mount_error`.
"""
from __future__ import annotations

import asyncio
from typing import Any, Callable, Dict, List, Optional, Tuple

from pyxpo import component, use_effect, use_imperative_handle, use_ref, use_state
from pyxpo.module_api import (NotSupportedError, call, native_view, permission_response, platform, require_linked,
                              snake_aliases, view_tag)

__version__ = "0.1.0a1"

__all__ = [
    "CameraView", "CameraViewHandle", "Camera", "BarcodeType", "BARCODE_TYPES", "PermissionStatus",
    "get_camera_permissions_async", "request_camera_permissions_async", "get_microphone_permissions_async",
    "request_microphone_permissions_async", "use_camera_permissions", "use_microphone_permissions",
    "is_available_async", "scan_from_url_async", "NotSupportedError",
]

_View = native_view("PxCameraView")

BARCODE_TYPES = ("aztec", "ean13", "ean8", "qr", "pdf417", "upc_e", "datamatrix", "code39", "code93", "itf14",
                 "codabar", "code128", "upc_a")


class BarcodeType:
    """Barcode type names (`barcode_scanner_settings["barcode_types"]`)."""
    AZTEC = "aztec"
    EAN13 = "ean13"
    EAN8 = "ean8"
    QR = "qr"
    PDF417 = "pdf417"
    UPC_E = "upc_e"
    DATAMATRIX = "datamatrix"
    CODE39 = "code39"
    CODE93 = "code93"
    ITF14 = "itf14"
    CODABAR = "codabar"
    CODE128 = "code128"
    UPC_A = "upc_a"


class PermissionStatus:
    GRANTED = "granted"
    DENIED = "denied"
    UNDETERMINED = "undetermined"


_FACING = ("front", "back")
_FLASH = ("off", "on", "auto")
_MODE = ("picture", "video")
_QUALITY = ("2160p", "1080p", "720p", "480p", "4:3")


def _native() -> None:
    if platform() == "desktop":
        raise NotSupportedError("camera needs iOS or Android")
    require_linked("Camera", "camera", package="pyxpo-camera")


def _choice(name: str, value: Any, allowed: Tuple[str, ...]) -> str:
    if value not in allowed:
        raise ValueError("%s must be one of %s, got %r" % (name, ", ".join(allowed), value))
    return value


def _unit(name: str, value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1:
        raise ValueError("%s must be a number from 0 to 1, got %r" % (name, value))
    return float(value)


def _positive(name: str, value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        raise ValueError("%s must be a number > 0, got %r" % (name, value))
    return float(value)


def _opt(o: Dict[str, Any], *names: str) -> Any:
    for n in names:
        if n in o:
            return o[n]
    return None


def _barcode_types(settings: Optional[Dict[str, Any]]) -> List[str]:
    if settings is None:
        return list(BARCODE_TYPES)
    if not isinstance(settings, dict):
        raise TypeError("barcode_scanner_settings must be a dict, got %s" % type(settings).__name__)
    types = _opt(settings, "barcode_types", "barcodeTypes")
    if types is None:
        return list(BARCODE_TYPES)
    if isinstance(types, str) or not isinstance(types, (list, tuple)) or not types:
        raise ValueError("barcode_types must be a non-empty list of %s" % ", ".join(BARCODE_TYPES))
    for t in types:
        _choice("barcode type", t, BARCODE_TYPES)
    return list(dict.fromkeys(types))


# permissions

def _permission(raw: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    raw = raw or {}
    return permission_response(str(raw.get("status", "denied")), bool(raw.get("canAskAgain", False)))


async def get_camera_permissions_async() -> Dict[str, Any]:
    """The camera permission, without asking (Expo's `PermissionResponse`)."""
    _native()
    return _permission(await call("Camera", "getPermission", "camera", reply=True))


async def request_camera_permissions_async() -> Dict[str, Any]:
    """Asks for the camera. iOS shows `NSCameraUsageDescription` (option `cameraPermission`)."""
    _native()
    return _permission(await call("Camera", "requestPermission", "camera", reply=True))


async def get_microphone_permissions_async() -> Dict[str, Any]:
    """The microphone permission (videos with sound), without asking."""
    _native()
    return _permission(await call("Camera", "getPermission", "microphone", reply=True))


async def request_microphone_permissions_async() -> Dict[str, Any]:
    """Asks for the microphone. iOS shows `NSMicrophoneUsageDescription` (option `microphonePermission`)."""
    _native()
    return _permission(await call("Camera", "requestPermission", "microphone", reply=True))


def _permission_hook(getter: Callable, requester: Callable, get: bool, request: bool) -> Tuple[Any, Callable, Callable]:
    status, set_status = use_state(None)

    async def ask() -> Dict[str, Any]:
        res = await requester()
        set_status(res)
        return res

    async def read() -> Dict[str, Any]:
        res = await getter()
        set_status(res)
        return res

    def start() -> None:
        if request:
            asyncio.ensure_future(ask())
        elif get:
            asyncio.ensure_future(read())

    use_effect(start, [])
    return status, ask, read


def use_camera_permissions(get: bool = True, request: bool = False) -> Tuple[Any, Callable, Callable]:
    """`(response, request, get)`: the camera permission (None until read), and coroutines that ask for it or read
    it again and update the component. `get=True` reads it on mount; `request=True` asks on mount."""
    return _permission_hook(get_camera_permissions_async, request_camera_permissions_async, get, request)


def use_microphone_permissions(get: bool = True, request: bool = False) -> Tuple[Any, Callable, Callable]:
    """Like `use_camera_permissions`, for the microphone."""
    return _permission_hook(get_microphone_permissions_async, request_microphone_permissions_async, get, request)


async def is_available_async() -> bool:
    """True when the device has a camera (False on the iOS Simulator and on desktop)."""
    if platform() == "desktop":
        return False
    require_linked("Camera", "camera", package="pyxpo-camera")
    return bool(await call("Camera", "isAvailable", reply=True))


async def scan_from_url_async(url: str, barcode_types: Optional[List[str]] = None) -> List[Dict[str, Any]]:
    """Barcodes in the image at `url` (a `file://` URI or a path), as `BarcodeScanningResult` dicts. Default types:
    `["qr"]`, as in Expo."""
    _native()
    if not isinstance(url, str) or not url:
        raise ValueError("url must be a non-empty string")
    types = _barcode_types({"barcode_types": barcode_types if barcode_types is not None else ["qr"]})
    return list(await call("Camera", "scanFromURL", url, types, reply=True) or [])


@snake_aliases
class Camera:
    """Expo's `Camera` namespace (permission functions)."""
    getCameraPermissionsAsync = staticmethod(get_camera_permissions_async)
    requestCameraPermissionsAsync = staticmethod(request_camera_permissions_async)
    getMicrophonePermissionsAsync = staticmethod(get_microphone_permissions_async)
    requestMicrophonePermissionsAsync = staticmethod(request_microphone_permissions_async)
    isAvailableAsync = staticmethod(is_available_async)
    scanFromURLAsync = staticmethod(scan_from_url_async)


# the view

class CameraViewHandle:
    """`ref.current` of a mounted `CameraView`."""

    def __init__(self, view_ref: Any) -> None:
        self._view = view_ref

    def _tag(self) -> int:
        _native()
        tag = view_tag(self._view)
        if tag is None:
            raise RuntimeError("the CameraView is not mounted")
        return tag

    async def take_picture_async(self, options: Optional[Dict[str, Any]] = None, **kw: Any) -> Dict[str, Any]:
        """A photo: `{"uri", "width", "height", "format"}` plus `base64` / `exif` when asked. Options: `quality`
        (0-1, default 1), `base64`, `exif`, `image_type` (`"jpg"` or `"png"`), `mirror` (front camera, default the
        view's `mirror`), `shutter_sound` (Android; iOS always plays it). The file is in the app cache."""
        o = {**(options or {}), **kw}
        tag = self._tag()
        quality = _opt(o, "quality")
        image_type = _opt(o, "image_type", "imageType") or "jpg"
        args = {
            "quality": 1.0 if quality is None else _unit("quality", quality),
            "base64": bool(o.get("base64", False)),
            "exif": bool(o.get("exif", False)),
            "imageType": _choice("image_type", image_type, ("jpg", "png")),
            "shutterSound": bool(_opt(o, "shutter_sound", "shutterSound") is not False),
        }
        mirror = o.get("mirror")
        if mirror is not None:
            args["mirror"] = bool(mirror)
        return await call("Camera", "takePicture", tag, args, reply=True)

    async def record_async(self, options: Optional[Dict[str, Any]] = None, **kw: Any) -> Optional[Dict[str, Any]]:
        """Records until `stop_recording()` or a limit, then returns `{"uri"}` (an `.mov` on iOS, `.mp4` on
        Android). The view needs `mode="video"`. Options: `max_duration` (seconds), `max_file_size` (bytes).
        Sound needs the microphone permission unless the view has `mute=True`."""
        o = {**(options or {}), **kw}
        tag = self._tag()
        args: Dict[str, Any] = {}
        d = _opt(o, "max_duration", "maxDuration")
        if d is not None:
            args["maxDuration"] = _positive("max_duration", d)
        s = _opt(o, "max_file_size", "maxFileSize")
        if s is not None:
            args["maxFileSize"] = int(_positive("max_file_size", s))
        return await call("Camera", "record", tag, args, reply=True)

    def stop_recording(self) -> None:
        call("Camera", "stopRecording", self._tag())

    async def pause_preview(self) -> None:
        await call("Camera", "pausePreview", self._tag(), reply=True)

    async def resume_preview(self) -> None:
        await call("Camera", "resumePreview", self._tag(), reply=True)

    async def get_available_picture_sizes_async(self) -> List[str]:
        """Photo sizes of the current camera, like `"4032x3024"`, largest first."""
        return list(await call("Camera", "pictureSizes", self._tag(), reply=True) or [])

    # Expo's camelCase names
    takePictureAsync = take_picture_async
    recordAsync = record_async
    stopRecording = stop_recording
    pausePreview = pause_preview
    resumePreview = resume_preview
    getAvailablePictureSizesAsync = get_available_picture_sizes_async


@component
def CameraView(facing: str = "back", flash: str = "off", enable_torch: bool = False, zoom: float = 0,
               mode: str = "picture", mute: bool = False, active: bool = True, mirror: bool = False,
               animate_shutter: bool = True, video_quality: Optional[str] = None,
               barcode_scanner_settings: Optional[Dict[str, Any]] = None,
               on_barcode_scanned: Optional[Callable[[Dict[str, Any]], Any]] = None,
               on_camera_ready: Optional[Callable[[], Any]] = None,
               on_mount_error: Optional[Callable[[Dict[str, Any]], Any]] = None,
               style: Any = None, **props: Any):
    """Expo's `CameraView`: a live preview sized by `style`. `ref.current` is a `CameraViewHandle`.

    `zoom` is 0 (none) to 1 (the most). `on_barcode_scanned(result)` gets `{"type", "data", "raw", "bounds",
    "cornerPoints"}` (points in the view, in pt/dp); scanning runs only while it is set. `on_mount_error`
    gets `{"message"}` when the camera cannot start (no camera, no permission)."""
    ref = props.pop("ref", None)
    inner = use_ref(None)
    use_imperative_handle(ref, lambda: CameraViewHandle(inner), [])
    wire: Dict[str, Any] = {
        "facing": _choice("facing", facing, _FACING), "flash": _choice("flash", flash, _FLASH),
        "enable_torch": bool(enable_torch), "zoom": _unit("zoom", zoom), "mode": _choice("mode", mode, _MODE),
        "mute": bool(mute), "active": bool(active), "mirror": bool(mirror), "animate_shutter": bool(animate_shutter),
    }
    if video_quality is not None:
        wire["video_quality"] = _choice("video_quality", video_quality, _QUALITY)
    if on_barcode_scanned is not None:
        wire["barcode_types"] = _barcode_types(barcode_scanner_settings)
        wire["on_barcode_scanned"] = on_barcode_scanned
    if on_camera_ready is not None:
        wire["on_camera_ready"] = on_camera_ready
    if on_mount_error is not None:
        wire["on_mount_error"] = on_mount_error
    return _View(ref=inner, style=style, **wire, **props)
