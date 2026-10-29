"""pyxpo-camera: expo-camera API over a fake bridge, and module views (`native_view`, `view_tag`)."""
import asyncio

import pytest

from pyxpo import LocalChannel, View, component, create_app, use_ref
from pyxpo.module_api import NotSupportedError, native_view, view_tag
from pyxpo.module_testing import FakeBridge
from pyxpo.renderers.mock import MockRenderer

import pyxpo_camera as Camera


def run(coro):
    return asyncio.run(coro)


def mount(root, **props):
    ch = LocalChannel()
    r = MockRenderer(ch)
    app = create_app(root, ch, **props)
    return app, r


def test_native_view_and_view_tag():
    with pytest.raises(ValueError):
        native_view("lower")
    with pytest.raises(ValueError):
        native_view("")
    Thing = native_view("PxThing")
    seen = []

    @component
    def Root():
        ref = use_ref(None)
        seen.append(ref)
        return View(Thing(ref=ref, size=3, on_tick=lambda v: None, style={"width": 10}))

    app, r = mount(Root)
    (node,) = r.find_all("PxThing")
    assert node.props["size"] == 3 and node.props["on_tick"] is True
    assert view_tag(seen[-1]) == node.id == view_tag(seen[-1].current)
    assert view_tag(None) is None and view_tag(object()) is None


def test_desktop():
    with pytest.raises(NotSupportedError):
        run(Camera.request_camera_permissions_async())
    with pytest.raises(NotSupportedError):
        run(Camera.scan_from_url_async("file:///x.png"))
    assert run(Camera.is_available_async()) is False


def test_not_linked():
    with FakeBridge("ios"):
        with pytest.raises(Exception, match="pyxpo-camera"):
            run(Camera.get_camera_permissions_async())


def test_permissions_and_availability():
    with FakeBridge("android") as fb:
        fb.on("Camera", "getPermission", lambda kind: {"status": "undetermined", "canAskAgain": True})
        fb.on("Camera", "requestPermission", lambda kind: {"status": "granted", "canAskAgain": True})
        fb.on("Camera", "isAvailable", lambda: True)
        p = run(Camera.get_camera_permissions_async())
        assert p["status"] == "undetermined" and not p["granted"] and p["canAskAgain"]
        assert run(Camera.Camera.requestMicrophonePermissionsAsync())["granted"]
        assert fb.calls[-1] == ("Camera", "requestPermission", ("microphone",))
        assert run(Camera.request_camera_permissions_async())["granted"]
        assert fb.calls[-1] == ("Camera", "requestPermission", ("camera",))
        assert run(Camera.is_available_async()) is True


def test_scan_from_url():
    hit = {"type": "qr", "data": "hello", "raw": "hello", "bounds": {}, "cornerPoints": []}
    with FakeBridge("ios") as fb:
        fb.on("Camera", "scanFromURL", lambda url, types: [hit])
        assert run(Camera.scan_from_url_async("file:///a.png")) == [hit]
        assert fb.calls[-1] == ("Camera", "scanFromURL", ("file:///a.png", ["qr"]))
        run(Camera.scan_from_url_async("/a.png", ["ean13", "qr", "ean13"]))
        assert fb.calls[-1][2][1] == ["ean13", "qr"]
        with pytest.raises(ValueError):
            run(Camera.scan_from_url_async("/a.png", ["nope"]))
        with pytest.raises(ValueError):
            run(Camera.scan_from_url_async(""))


def test_camera_view_props_and_events():
    got = []

    @component
    def Root():
        return Camera.CameraView(facing="front", zoom=0.5, mode="video", video_quality="720p",
                                 barcode_scanner_settings={"barcode_types": ["qr", "ean13"]},
                                 on_barcode_scanned=got.append, on_camera_ready=lambda: got.append("ready"),
                                 style={"flex": 1})

    app, r = mount(Root)
    (node,) = r.find_all("PxCameraView")
    p = node.props
    assert p["facing"] == "front" and p["zoom"] == 0.5 and p["mode"] == "video" and p["video_quality"] == "720p"
    assert p["barcode_types"] == ["qr", "ean13"] and p["on_barcode_scanned"] is True and p["on_camera_ready"] is True
    assert "on_mount_error" not in p
    r.emit("camera_ready", node, {"value": None})
    r.emit("barcode_scanned", node, {"value": {"type": "qr", "data": "x"}})
    assert got == ["ready", {"type": "qr", "data": "x"}]


def test_camera_view_without_scanner_has_no_types():
    @component
    def Root():
        return Camera.CameraView()

    app, r = mount(Root)
    (node,) = r.find_all("PxCameraView")
    assert "barcode_types" not in node.props and node.props["active"] is True and node.props["flash"] == "off"


@pytest.mark.parametrize("kw", [{"facing": "side"}, {"flash": "torch"}, {"zoom": 2}, {"mode": "photo"},
                                {"video_quality": "8k"},
                                {"on_barcode_scanned": print, "barcode_scanner_settings": {"barcode_types": "qr"}}])
def test_camera_view_bad_props(kw):
    @component
    def Root():
        return Camera.CameraView(**kw)

    with pytest.raises((ValueError, TypeError)):
        mount(Root, on_error=lambda e: (_ for _ in ()).throw(e))


def test_handle_methods():
    refs = []

    @component
    def Root():
        ref = use_ref(None)
        refs.append(ref)
        return Camera.CameraView(ref=ref, mode="picture")

    app, r = mount(Root)  # the app sets the desktop env, so the fake bridge comes after
    with FakeBridge("ios") as fb:
        tag = r.find_all("PxCameraView")[0].id
        fb.on("Camera", "takePicture", lambda t, o: {"uri": "file:///p.jpg", "width": 4, "height": 3, "format": "jpg"})
        fb.on("Camera", "record", lambda t, o: {"uri": "file:///v.mov"})
        fb.on("Camera", "pausePreview", lambda t: None)
        fb.on("Camera", "resumePreview", lambda t: None)
        fb.on("Camera", "pictureSizes", lambda t: ["4032x3024", "1920x1080"])
        fb.on("Camera", "stopRecording", lambda t: None)
        cam = refs[-1].current
        assert isinstance(cam, Camera.CameraViewHandle)
        photo = run(cam.take_picture_async({"quality": 0.5, "base64": True}))
        assert photo["uri"] == "file:///p.jpg"
        assert fb.calls[-1] == ("Camera", "takePicture", (tag, {"quality": 0.5, "base64": True, "exif": False,
                                                               "imageType": "jpg", "shutterSound": True}))
        run(cam.takePictureAsync(mirror=True, image_type="png", shutter_sound=False))
        assert fb.calls[-1][2][1]["mirror"] is True and fb.calls[-1][2][1]["imageType"] == "png"
        assert fb.calls[-1][2][1]["shutterSound"] is False
        assert run(cam.record_async(max_duration=2, max_file_size=1e6)) == {"uri": "file:///v.mov"}
        assert fb.calls[-1] == ("Camera", "record", (tag, {"maxDuration": 2.0, "maxFileSize": 1000000}))
        cam.stop_recording()
        assert fb.calls[-1][:2] == ("Camera", "stopRecording")
        run(cam.pause_preview())
        run(cam.resume_preview())
        assert run(cam.get_available_picture_sizes_async()) == ["4032x3024", "1920x1080"]
        with pytest.raises(ValueError):
            run(cam.take_picture_async(quality=3))
        with pytest.raises(ValueError):
            run(cam.record_async(max_duration=0))
