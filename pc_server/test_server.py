import asyncio
import base64
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, MagicMock, patch

import server


class ServerProtocolTests(unittest.TestCase):
    TOKEN = "654321"
    CLIENT = ("192.0.2.10", 40000)

    def make_server(self):
        instance = object.__new__(server.Server)
        instance.auth_token = self.TOKEN
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_client_callback = None
        instance.on_packet_callback = None
        instance.last_buttons = 0
        instance.last_pen_button = None
        instance.last_input_at = 0
        instance.pressed_keys = set()
        instance.mouse = Mock()
        instance.keyboard = Mock()
        return instance

    def test_windows_performance_payload_groups_gpu_engines_and_keeps_npu_unknown(self):
        payload = {
            "disk_activity": 42.36,
            "adapters": ["Intel Graphics", "NVIDIA GeForce"],
            "gpu_samples": [
                {"Path": r"\\PC\GPU Engine(pid_1_luid_0x0_phys_0_eng_0_engtype_3D)\Utilization Percentage", "CookedValue": 22.0},
                {"Path": r"\\PC\GPU Engine(pid_1_luid_0x0_phys_0_eng_1_engtype_Copy)\Utilization Percentage", "CookedValue": 70.0},
                {"Path": r"\\PC\GPU Engine(pid_1_luid_0x0_phys_1_eng_0_engtype_3D)\Utilization Percentage", "CookedValue": 31.2},
                {"Path": r"\\PC\GPU Engine(pid_2_luid_0x0_phys_1_eng_2_engtype_3D)\Utilization Percentage", "CookedValue": 12.0},
                {"Path": r"\\PC\GPU Engine(pid_1_luid_0x0_phys_1_eng_1_engtype_VideoDecode)\Utilization Percentage", "CookedValue": 8.0},
            ],
        }

        metrics = server.Server._parse_windows_performance_payload(payload)

        self.assertEqual(metrics["disk_activity"], 42.4)
        self.assertEqual(metrics["gpus"], [
            {"index": 0, "name": "Intel Graphics", "utilization": 70.0, "engine": "Copy"},
            {"index": 1, "name": "NVIDIA GeForce", "utilization": 43.2, "engine": "3D"},
        ])
        self.assertEqual(metrics["npu"], -1.0)

    def test_windows_performance_payload_handles_single_counter_and_missing_values(self):
        metrics = server.Server._parse_windows_performance_payload({
            "disk_activity": -1,
            "adapters": "Integrated GPU",
            "gpu_samples": {"Path": r"\\PC\GPU Engine(pid_1_luid_0x0_phys_0_eng_0_engtype_3D)\Utilization Percentage", "CookedValue": 140},
        })

        self.assertEqual(metrics["disk_activity"], -1.0)
        self.assertEqual(metrics["gpus"][0]["utilization"], 100.0)
        self.assertEqual(metrics["npu"], -1.0)

    def test_udp_connect_requires_pin_and_returns_ack(self):
        instance = self.make_server()
        responses = []

        instance.handle_packet(
            {"type": "connect", "client": "Phone", "token": "wrong"},
            self.CLIENT,
            responses.append,
        )

        self.assertEqual(responses[0]["type"], "connect_ack")
        self.assertFalse(responses[0]["success"])
        self.assertIsNone(instance.connected_client)

        responses.clear()
        instance.handle_packet(
            {"type": "connect", "client": "Phone", "token": self.TOKEN},
            self.CLIENT,
            responses.append,
        )

        self.assertEqual(responses[0]["type"], "connect_ack")
        self.assertTrue(responses[0]["success"])
        self.assertEqual(instance.connected_client, "Phone (192.0.2.10)")

    def test_unauthorized_query_and_launch_have_no_side_effect(self):
        instance = self.make_server()
        instance._get_installed_apps = Mock(return_value=[])
        instance._launch_application = Mock()
        responses = []

        instance.handle_packet({"type": "query_apps"}, self.CLIENT, responses.append)
        instance.handle_packet(
            {"type": "launch_app", "app": "calc"}, self.CLIENT, responses.append
        )

        instance._get_installed_apps.assert_not_called()
        instance._launch_application.assert_not_called()
        self.assertEqual([response["type"] for response in responses], ["auth_error"] * 2)

    def test_phone_quick_apps_update_accepts_only_discovered_shortcuts(self):
        instance = self.make_server()
        shortcut = r"C:\ProgramData\Microsoft\Windows\Start Menu\Programs\Browser.lnk"
        discovered = [{"name": "Browser", "path": shortcut, "is_quick": False}]
        instance._query_cache_lock = threading.Lock()
        instance._query_cache = {"apps": (server.time.monotonic(), discovered)}
        responses = []

        with patch.object(server, "save_quick_apps", side_effect=lambda apps: apps) as save:
            instance.handle_packet({
                "type": "update_quick_apps",
                "paths": [shortcut, r"C:\outside\untrusted.lnk"],
                "token": self.TOKEN,
            }, self.CLIENT, responses.append)

        self.assertEqual(save.call_args.args[0], [{"name": "Browser", "path": shortcut}])
        self.assertTrue(responses[0]["success"])
        self.assertEqual(responses[0]["paths"], [shortcut])
        self.assertTrue(discovered[0]["is_quick"])

    def test_slow_task_refresh_returns_snapshot_before_loader_finishes(self):
        instance = object.__new__(server.Server)
        instance._query_cache = {}
        instance._query_cache_lock = threading.Lock()
        instance._query_cache_key_locks = {}
        instance._async_query_lock = threading.Lock()
        instance._async_query_waiters = {}
        instance._async_query_running = set()
        scan_started = threading.Event()
        release_scan = threading.Event()
        refresh_delivered = threading.Event()
        responses = []

        def slow_scan():
            scan_started.set()
            self.assertTrue(release_scan.wait(2.0))
            return [{"pid": 42, "name": "player.exe"}]

        def respond(payload):
            responses.append(payload)
            if payload.get("loading") is False:
                refresh_delivered.set()

        instance._respond_query_async(
            "processes", 3.0, slow_scan, "process_list_response", "processes", [],
            ("WebSocket", 1), respond
        )
        self.assertTrue(scan_started.wait(1.0))
        self.assertTrue(responses[0]["loading"])
        self.assertEqual(responses[0]["processes"], [])
        release_scan.set()
        self.assertTrue(refresh_delivered.wait(2.0))
        self.assertEqual(responses[-1]["processes"][0]["pid"], 42)

    def test_button_command_alias_press_and_release(self):
        instance = self.make_server()
        button = SimpleNamespace(left=object(), right=object(), middle=object())
        with patch.object(server, "IS_WINDOWS", False), patch.object(
            server, "Button", button, create=True
        ):
            instance.handle_packet(
                {"type": "button", "btn": 1, "token": self.TOKEN}, self.CLIENT
            )
            instance.handle_packet(
                {"type": "mouse_button", "btn": 0, "token": self.TOKEN}, self.CLIENT
            )

        instance.mouse.press.assert_called_once_with(button.left)
        instance.mouse.release.assert_called_once_with(button.left)
        self.assertEqual(instance.last_buttons, 0)

    def test_unrecognized_application_identifier_is_rejected(self):
        instance = self.make_server()
        with patch.object(server, "IS_WINDOWS", False):
            self.assertFalse(instance._launch_application("calc & whoami"))

    def test_udp_response_is_json_and_respects_datagram_bound(self):
        instance = self.make_server()
        instance.sock = Mock()

        instance._send_udp_json({"type": "pong", "time": 1}, self.CLIENT)

        payload, address = instance.sock.sendto.call_args.args
        self.assertEqual(json.loads(payload), {"type": "pong", "time": 1})
        self.assertEqual(address, self.CLIENT)

        instance.sock.reset_mock()
        instance._send_udp_json({"data": "x" * 65000}, self.CLIENT)
        instance.sock.sendto.assert_not_called()

    def test_pairing_pin_is_stable_between_server_restarts(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as folder:
            config_path = Path(folder) / "pairing.json"
            with patch.object(server, "PAIRING_PIN_PATH", config_path):
                first_pin = server.load_or_create_pairing_pin()
                second_pin = server.load_or_create_pairing_pin()

        self.assertRegex(first_pin, r"^\d{6}$")
        self.assertEqual(second_pin, first_pin)

    def test_pairing_pin_rejects_invalid_values(self):
        with self.assertRaises(ValueError):
            server.save_pairing_pin("abc123")

    def test_recording_retention_removes_only_expired_unprotected_files(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as folder:
            old_file = Path(folder) / "old.avi"
            active_file = Path(folder) / "active.avi"
            old_file.write_bytes(b"old")
            active_file.write_bytes(b"active")
            old_time = server.time.time() - 3 * 86400
            os.utime(old_file, (old_time, old_time))
            os.utime(active_file, (old_time, old_time))

            allowed, message = server.prepare_recording_storage(
                folder, retention_days=1, min_free_mb=0, protected_paths=(active_file,)
            )

            self.assertTrue(allowed, message)
            self.assertFalse(old_file.exists())
            self.assertTrue(active_file.exists())

    def test_recording_delete_removes_file_and_returns_updated_catalog(self):
        srv = self.make_server()
        with tempfile.TemporaryDirectory() as td:
            folder = Path(td)
            test_rec = folder / "audio_20261024_120000.wav"
            test_rec.write_bytes(b"RIFF" + b"\x00" * 100)
            srv.audio_manager = SimpleNamespace(recordings_dir=str(folder), recording=False, current_filename=None)
            srv.camera_manager = SimpleNamespace(streaming=False, last_recording_path=None, stream_thread=None)
            srv.live_audio_manager = SimpleNamespace(capture_path=None, output_path=None, touch_owner=Mock())

            responses = []
            srv.handle_packet(
                {"type": "recording_delete", "id": test_rec.name, "token": self.TOKEN},
                self.CLIENT,
                responses.append
            )

            self.assertFalse(test_rec.exists())
            self.assertEqual(responses[0]["type"], "recording_delete_response")
            self.assertTrue(responses[0]["success"])
            self.assertEqual(responses[1]["type"], "recordings_list_response")
            self.assertEqual(responses[1]["files"], [])

    def test_snapshot_saved_to_directory_and_listed_and_deleted(self):
        srv = self.make_server()
        with tempfile.TemporaryDirectory() as td:
            folder = Path(td)
            snaps_dir = folder / "Snapshots"
            snaps_dir.mkdir()
            test_snap = snaps_dir / "snapshot_20261024_120000.jpg"
            test_snap.write_bytes(b"\xFF\xD8\xFF\xE0" + b"\x00" * 50)
            srv.audio_manager = SimpleNamespace(recordings_dir=str(folder), recording=False, current_filename=None)
            srv.camera_manager = SimpleNamespace(streaming=False, last_recording_path=None, stream_thread=None, snapshots_dir=str(snaps_dir))
            srv.live_audio_manager = SimpleNamespace(capture_path=None, output_path=None, touch_owner=Mock())

            catalog = srv._get_recordings_list()
            self.assertEqual(len(catalog["files"]), 1)
            self.assertEqual(catalog["files"][0]["id"], test_snap.name)
            self.assertEqual(catalog["files"][0]["media_type"], "snapshot")

            responses = []
            srv.handle_packet(
                {"type": "recording_delete", "id": test_snap.name, "token": self.TOKEN},
                self.CLIENT,
                responses.append
            )

            self.assertFalse(test_snap.exists())
            self.assertEqual(responses[0]["type"], "recording_delete_response")
            self.assertTrue(responses[0]["success"])
            self.assertEqual(responses[1]["files"], [])


class CameraLifecycleTests(unittest.TestCase):
    def test_stop_releases_capture_to_unblock_camera_read(self):
        entered_read = threading.Event()
        released = threading.Event()

        class FakeCapture:
            def isOpened(self):
                return True

            def set(self, _property, _value):
                return True

            def read(self):
                entered_read.set()
                released.wait(timeout=3)
                return False, None

            def release(self):
                released.set()

        fake_cv2 = SimpleNamespace(
            CAP_DSHOW=700,
            CAP_PROP_FRAME_WIDTH=3,
            CAP_PROP_FRAME_HEIGHT=4,
            VideoCapture=lambda *_args: FakeCapture(),
        )
        manager = server.CameraManager()
        manager.has_cv2 = True
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as recordings_dir:
            manager.recordings_dir = recordings_dir
            with patch.dict(sys.modules, {"cv2": fake_cv2}):
                self.assertTrue(manager.start_stream(lambda _frame: None, fps=30))
                self.assertTrue(entered_read.wait(timeout=1))
                self.assertTrue(manager.stop_stream())

        self.assertTrue(released.is_set())
        self.assertFalse(manager.streaming)
        self.assertIsNone(manager.stream_capture)

    def test_camera_stream_stops_and_reports_repeated_empty_reads(self):
        ended = threading.Event()
        reasons = []

        class EmptyCapture:
            released = False

            def isOpened(self):
                return True

            def set(self, _property, _value):
                return True

            def read(self):
                return False, None

            def release(self):
                self.released = True

        capture = EmptyCapture()
        fake_cv2 = SimpleNamespace(
            CAP_DSHOW=700,
            CAP_PROP_FRAME_WIDTH=3,
            CAP_PROP_FRAME_HEIGHT=4,
            VideoCapture=lambda *_args: capture,
        )
        manager = server.CameraManager()
        manager.has_cv2 = True

        def on_stream_end(reason):
            reasons.append(reason)
            ended.set()

        with patch.dict(sys.modules, {"cv2": fake_cv2}):
            self.assertTrue(manager.start_stream(lambda _frame: None, fps=30,
                                                 on_stream_end_callback=on_stream_end))
            self.assertTrue(ended.wait(timeout=2))

        self.assertFalse(manager.streaming)
        self.assertIsNone(manager.stream_capture)
        self.assertTrue(capture.released)
        self.assertEqual(reasons, ["The camera stopped returning frames."])

    def test_wifi_stream_reduces_frame_size_until_jpeg_fits_datagram_budget(self):
        frame_arrived = threading.Event()
        frames = []

        class Frame:
            def __init__(self, width, height):
                self.shape = (height, width, 3)

        class EncodedBuffer(bytes):
            @property
            def nbytes(self):
                return len(self)

        class Capture:
            def isOpened(self):
                return True

            def set(self, _property, _value):
                return True

            def read(self):
                return True, Frame(160, 90)

            def release(self):
                pass

        class FakeCv2:
            CAP_DSHOW = 700
            CAP_PROP_FRAME_WIDTH = 3
            CAP_PROP_FRAME_HEIGHT = 4
            IMWRITE_JPEG_QUALITY = 5

            @staticmethod
            def VideoCapture(*_args):
                return Capture()

            @staticmethod
            def resize(_frame, dimensions):
                return Frame(*dimensions)

            @staticmethod
            def imencode(_extension, frame, _parameters):
                width = frame.shape[1]
                size = 20_000 if width > 80 else 8_000
                return True, EncodedBuffer(b"x" * size)

        manager = server.CameraManager()
        manager.has_cv2 = True
        with patch.dict(sys.modules, {"cv2": FakeCv2}):
            self.assertTrue(manager.start_stream(
                lambda frame: (frames.append(frame), frame_arrived.set()),
                max_width=160,
                max_frame_bytes=16_000,
                fps=30,
            ))
            self.assertTrue(frame_arrived.wait(timeout=2))
            self.assertTrue(manager.stop_stream())

        self.assertEqual(len(base64.b64decode(frames[0])), 8_000)

    def test_unexpected_camera_end_clears_owner_stops_linked_audio_and_notifies_phone(self):
        instance = object.__new__(server.Server)
        instance._camera_stream_owner_lock = threading.Lock()
        instance._camera_stream_owner = {"key": ("wan", None), "session_id": "session-1"}
        instance._camera_auto_audio_recording = True
        instance.audio_manager = Mock()
        responses = []

        instance._on_camera_stream_ended(
            "session-1", ("wan", None), responses.append, "The camera stopped returning frames."
        )

        self.assertIsNone(instance._camera_stream_owner)
        self.assertFalse(instance._camera_auto_audio_recording)
        instance.audio_manager.stop_recording.assert_called_once_with()
        self.assertEqual(responses, [{
            "type": "camera_stream_status",
            "streaming": False,
            "message": "The camera stopped returning frames.",
        }])


class MediaStateTests(unittest.TestCase):
    def test_disappeared_media_session_clears_stale_track(self):
        controller = object.__new__(server.MediaController)
        controller.has_winrt = False
        controller.has_pycaw = False
        controller.last_state = {
            "has_media": True,
            "title": "Old YouTube video",
            "artist": "Browser audio",
            "album": "",
            "status": "Playing",
            "position": 12.0,
            "duration": 0.0,
            "volume": 0.5,
            "muted": False,
        }
        controller.get_volume = Mock(return_value=(0.5, False))
        controller._detect_browser_audio_session = Mock(return_value=None)

        state = controller.get_media_state()

        self.assertFalse(state["has_media"])
        self.assertEqual(state["status"], "NoMedia")
        self.assertEqual(state["title"], "")

    def test_com_context_tolerates_rpc_e_changed_mode(self):
        controller = object.__new__(server.MediaController)
        err = OSError()
        err.winerror = -2147417850  # 0x80010106 RPC_E_CHANGED_MODE
        with patch.dict("sys.modules", {"comtypes": Mock(CoInitialize=Mock(side_effect=err), CoUninitialize=Mock())}):
            import comtypes
            with controller._com_context():
                pass
            comtypes.CoUninitialize.assert_not_called()

    def test_volume_control_updates_level_and_muted(self):
        controller = object.__new__(server.MediaController)
        controller.has_pycaw = True
        controller.last_state = {}
        mock_endpoint = Mock()
        mock_endpoint.GetMasterVolumeLevelScalar.return_value = 0.75
        mock_endpoint.GetMute.return_value = 0
        mock_speakers = Mock(EndpointVolume=mock_endpoint)
        controller.audio_utilities = Mock(GetSpeakers=Mock(return_value=mock_speakers))
        controller._com_context = Mock()
        controller._com_context.return_value.__enter__ = Mock(return_value=None)
        controller._com_context.return_value.__exit__ = Mock(return_value=None)

        success = controller.set_volume(0.75)
        self.assertTrue(success)
        self.assertEqual(controller.last_state["volume"], 0.75)
        self.assertFalse(controller.last_state["muted"])
        self.assertTrue(controller.last_state["volume_available"])

        mock_endpoint.GetMute.return_value = 1
        success_mute = controller.set_mute(True)
        self.assertTrue(success_mute)
        self.assertTrue(controller.last_state["muted"])

    def test_active_smtc_session_prioritizes_playing_over_paused(self):
        controller = object.__new__(server.MediaController)
        controller.has_winrt = True
        session_paused = Mock()
        session_paused.get_playback_info.return_value = Mock(playback_status=5)  # Paused
        session_playing = Mock()
        session_playing.get_playback_info.return_value = Mock(playback_status=4)  # Playing

        mgr = Mock()
        mgr.get_sessions.return_value = [session_paused, session_playing]
        mgr.get_current_session.return_value = session_paused

        async def _mock_req():
            return mgr

        controller.wmc = Mock(GlobalSystemMediaTransportControlsSessionManager=Mock(
            request_async=_mock_req
        ))

        chosen = asyncio.run(controller._active_smtc_session())
        self.assertEqual(chosen, session_playing)


class QuickAppsTests(unittest.TestCase):
    def test_update_quick_apps_falls_back_when_cache_empty(self):
        instance = object.__new__(server.Server)
        instance.auth_token = "123456"
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_packet_callback = None
        instance._query_cache_lock = threading.Lock()
        instance._query_cache = {}
        shortcut = r"C:\ProgramData\Microsoft\Windows\Start Menu\Programs\TestApp.lnk"
        instance._get_installed_apps = Mock(return_value=[{"name": "TestApp", "path": shortcut, "is_quick": False}])
        responses = []

        with patch("server.load_quick_apps", return_value=[]), patch("server.save_quick_apps", return_value=[{"name": "TestApp", "path": shortcut}]):
            instance.handle_packet(
                {"type": "update_quick_apps", "paths": [shortcut], "token": "123456"},
                ("127.0.0.1", 9999),
                responses.append
            )

        self.assertTrue(responses)
        self.assertTrue(responses[0].get("success"))
        self.assertEqual(responses[0].get("paths"), [shortcut])


class StartupSelfHealingTests(unittest.TestCase):
    def test_heal_startup_path_when_registry_value_mismatched(self):
        import app_gui
        app_mock = object.__new__(app_gui.CompanionApp)
        app_mock._startup_registry_value = Mock(return_value=r'"C:\Old\path.exe" --minimized')
        app_mock._startup_command = Mock(return_value=r'"C:\New\path.exe" --minimized')
        app_mock.log = Mock()

        mock_winreg = MagicMock()
        mock_key = MagicMock()
        mock_winreg.CreateKeyEx.return_value.__enter__.return_value = mock_key
        mock_winreg.HKEY_CURRENT_USER = 1
        mock_winreg.KEY_SET_VALUE = 2
        mock_winreg.REG_SZ = 3

        with patch.dict(sys.modules, {"winreg": mock_winreg}):
            app_mock._heal_startup_path_if_registered()

        mock_winreg.SetValueEx.assert_called_once_with(
            mock_key, "BT Mouse Companion", 0, mock_winreg.REG_SZ, r'"C:\New\path.exe" --minimized'
        )
        app_mock.log.assert_called_once()

    def test_heal_startup_path_skips_when_matching(self):
        import app_gui
        app_mock = object.__new__(app_gui.CompanionApp)
        app_mock._startup_registry_value = Mock(return_value=r'"C:\Current\path.exe" --minimized')
        app_mock._startup_command = Mock(return_value=r'"C:\Current\path.exe" --minimized')
        app_mock.log = Mock()

        mock_winreg = Mock()
        with patch.dict(sys.modules, {"winreg": mock_winreg}):
            app_mock._heal_startup_path_if_registered()

        mock_winreg.SetValueEx.assert_not_called()


if __name__ == "__main__":
    unittest.main()
