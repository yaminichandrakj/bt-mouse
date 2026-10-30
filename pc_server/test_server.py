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
import remote_tunnel
import update_script


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
            self.assertEqual(responses[1]["type"], "recordings_list_response")
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

        with patch("os.name", "nt"), patch.dict(sys.modules, {"winreg": mock_winreg}):
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
        with patch("os.name", "nt"), patch.dict(sys.modules, {"winreg": mock_winreg}):
            app_mock._heal_startup_path_if_registered()

        mock_winreg.SetValueEx.assert_not_called()


class ConcurrentTransportFailoverTests(unittest.TestCase):
    def test_ping_echoes_client_time(self):
        instance = object.__new__(server.Server)
        instance.auth_token = "123456"
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_packet_callback = None
        instance._camera_stream_owner_lock = threading.Lock()
        instance.live_audio_manager = Mock()
        responses = []

        instance.handle_packet(
            {"type": "ping", "client_time": 987654, "token": "123456"},
            ("127.0.0.1", 9999),
            responses.append
        )
        self.assertEqual(len(responses), 1)
        self.assertEqual(responses[0]["type"], "pong")
        self.assertEqual(responses[0]["client_time"], 987654)

    def test_sequence_deduplication_drops_duplicate_commands(self):
        instance = object.__new__(server.Server)
        instance.auth_token = "123456"
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_packet_callback = None
        instance._camera_stream_owner_lock = threading.Lock()
        instance.live_audio_manager = Mock()
        instance._cmd_seq_lock = threading.Lock()
        instance._last_cmd_seq = {}
        instance.last_buttons = 0
        instance.last_pen_button = None
        instance.pressed_keys = set()
        instance.last_input_at = 0
        instance.mouse = Mock()

        # First command with seq 5
        instance.handle_packet(
            {"type": "mouse", "dx": 10, "dy": 0, "seq": 5, "client_session": "sess-1", "token": "123456"},
            ("127.0.0.1", 9999),
            lambda resp: None
        )
        self.assertEqual(instance._last_cmd_seq.get("sess-1"), 5)

        # Duplicate command with seq 5 (e.g. delivered via overlapping transport)
        instance.handle_packet(
            {"type": "mouse", "dx": 10, "dy": 0, "seq": 5, "client_session": "sess-1", "token": "123456"},
            ("192.168.1.50", 8888),
            lambda resp: None
        )
        # Should remain 5 and not update or execute
        self.assertEqual(instance._last_cmd_seq.get("sess-1"), 5)

    def test_out_of_order_udp_packets_are_accepted_while_duplicates_are_dropped(self):
        instance = object.__new__(server.Server)
        instance.auth_token = "123456"
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_packet_callback = None
        instance._camera_stream_owner_lock = threading.Lock()
        instance.live_audio_manager = Mock()
        instance._cmd_seq_lock = threading.Lock()
        instance._last_cmd_seq = {}
        instance.last_buttons = 0
        instance.last_pen_button = None
        instance.pressed_keys = set()
        instance.last_input_at = 0
        instance.mouse = Mock()

        with patch.object(server, "IS_WINDOWS", False):
            # Packet seq 10 arrives first
            instance.handle_packet(
                {"type": "mouse", "dx": 5, "dy": 0, "seq": 10, "client_session": "sess-2", "token": "123456"},
                ("127.0.0.1", 9999),
                lambda resp: None
            )
            # Slightly delayed UDP packet seq 9 arrives out-of-order -> must still execute!
            instance.handle_packet(
                {"type": "mouse", "dx": 3, "dy": 0, "seq": 9, "client_session": "sess-2", "token": "123456"},
                ("127.0.0.1", 9999),
                lambda resp: None
            )
            # Duplicate seq 9 arrives again -> must be dropped!
            instance.handle_packet(
                {"type": "mouse", "dx": 3, "dy": 0, "seq": 9, "client_session": "sess-2", "token": "123456"},
                ("127.0.0.1", 9999),
                lambda resp: None
            )

        self.assertEqual(instance.mouse.move.call_count, 2)

    def test_path_switch_releases_mouse_buttons(self):
        instance = object.__new__(server.Server)
        instance.auth_token = "123456"
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_packet_callback = None
        instance._camera_stream_owner_lock = threading.Lock()
        instance.live_audio_manager = Mock()
        instance._cmd_seq_lock = threading.Lock()
        instance._last_cmd_seq = {}
        instance.last_buttons = 1
        instance.last_pen_button = None
        instance.pressed_keys = set()
        instance.mouse = Mock()
        instance.keyboard = None
        responses = []

        instance.handle_packet(
            {"type": "path_switch", "token": "123456"},
            ("127.0.0.1", 9999),
            responses.append
        )
        self.assertEqual(instance.last_buttons, 0)
        self.assertEqual(responses, [{"type": "path_switch_ack", "success": True}])


class PhoneCameraSourceTests(unittest.TestCase):
    def setUp(self):
        self.instance = object.__new__(server.Server)
        self.instance.auth_token = "123456"
        self.instance.auth_failures = {}
        self.instance.connected_client = None
        self.instance.on_packet_callback = None
        self.instance.on_phone_cam_callback = None
        self.instance._camera_stream_owner_lock = threading.Lock()
        self.instance.live_audio_manager = Mock()
        self.instance._cmd_seq_lock = threading.Lock()
        self.instance._last_cmd_seq = {}
        self.instance.phone_camera_receiver = server.PhoneCameraReceiver()
        self.instance.phone_camera_http = Mock()
        self.instance.phone_camera_http.port = 8766
        self.instance.get_local_ip = lambda: "192.168.1.100"

    def test_phone_cam_start_acknowledgement(self):
        responses = []
        self.instance.handle_packet(
            {"type": "phone_cam_start", "token": "123456", "lens": "front", "width": 1280, "height": 720},
            ("127.0.0.1", 9999),
            responses.append
        )
        self.assertEqual(len(responses), 1)
        resp = responses[0]
        self.assertEqual(resp["type"], "phone_cam_start_ack")
        self.assertTrue(resp["success"])
        self.assertIn("127.0.0.1:8766/phone_cam/mjpeg", resp["mjpeg_url"])
        self.assertTrue(self.instance.phone_camera_receiver.is_active)
        self.assertEqual(self.instance.phone_camera_receiver.lens, "front")
        self.assertEqual(self.instance.phone_camera_receiver.width, 1280)
        self.assertEqual(self.instance.phone_camera_receiver.height, 720)

    def test_phone_cam_restart_releases_existing_virtual_cam(self):
        existing_vcam = Mock()
        self.instance.phone_camera_receiver.virtual_cam = existing_vcam
        self.instance.phone_camera_receiver.start("front", 640, 360)
        existing_vcam.close.assert_called_once_with()

    def test_phone_cam_frame_updates_receiver_and_dispatches_callback(self):
        dispatched = []
        self.instance.on_phone_cam_callback = lambda raw, w, h, lens: dispatched.append((raw, w, h, lens))
        test_frame = b"\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xFF\xDB\x00C\x00\xFF\xD9"
        b64 = base64.b64encode(test_frame).decode("ascii")

        self.instance.handle_packet(
            {"type": "phone_cam_frame", "token": "123456", "lens": "back", "w": 640, "h": 360, "frame": b64},
            ("127.0.0.1", 9999),
            lambda r: None
        )

        self.assertEqual(self.instance.phone_camera_receiver.latest_jpeg, test_frame)
        self.assertEqual(self.instance.phone_camera_receiver.frame_count, 1)
        self.assertEqual(len(dispatched), 1)
        self.assertEqual(dispatched[0], (test_frame, 640, 360, "back"))

        status = self.instance.phone_camera_receiver.get_status()
        self.assertTrue(status["active"])
        self.assertTrue(status["has_frame"])
        self.assertEqual(status["width"], 640)
        self.assertEqual(status["height"], 360)

    def test_phone_cam_stop_resets_state(self):
        self.instance.phone_camera_receiver.start("back", 640, 360)
        self.instance.phone_camera_receiver.update_frame(b"dummy_jpeg", 640, 360, "back")
        self.assertTrue(self.instance.phone_camera_receiver.is_active)

        responses = []
        self.instance.handle_packet(
            {"type": "phone_cam_stop", "token": "123456"},
            ("127.0.0.1", 9999),
            responses.append
        )
        self.assertEqual(responses, [{"type": "phone_cam_stop_ack", "success": True}])
        self.assertFalse(self.instance.phone_camera_receiver.is_active)
        self.assertIsNone(self.instance.phone_camera_receiver.latest_jpeg)


class DpiAndQrRemediationTests(unittest.TestCase):
    def test_dpi_scale_returns_positive_numbers(self):
        import app_gui
        scale, dpi = app_gui.get_windows_dpi_scale()
        self.assertGreaterEqual(scale, 1.0)
        self.assertGreaterEqual(dpi, 96)
        app_gui.set_windows_dpi_awareness()

    def test_qr_payloads_include_pairing_pin_and_udp_buffer_is_65507(self):
        gui_source = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        srv_source = (Path(__file__).parent / "server.py").read_text(encoding="utf-8")
        self.assertIn('"pin": self.auth_token', gui_source)
        self.assertIn('"token": self.auth_token', gui_source)
        self.assertIn("self.sock.recvfrom(65507)", srv_source)
        self.assertIn('ThreadingHTTPServer(("127.0.0.1", p)', srv_source)


class V1317ArchitectureAndFailoverTests(unittest.TestCase):
    def test_media_dirs_separated_into_videos_audios_snapshots_and_migrated(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            base = Path(tmpdir) / "BTMouse"
            legacy_rec = base / "Recordings"
            legacy_rec.mkdir(parents=True, exist_ok=True)
            (legacy_rec / "webcam_20261024_120000.mp4").write_bytes(b"video-data")
            (legacy_rec / "audio_20261024_120000.wav").write_bytes(b"audio-data")
            (legacy_rec / "snapshot_20261024_120000.jpg").write_bytes(b"image-data")

            with patch.dict(os.environ, {"LOCALAPPDATA": tmpdir}):
                dirs = server.get_btmouse_media_dirs()

            self.assertTrue(Path(dirs["videos"]).is_dir())
            self.assertTrue(Path(dirs["audios"]).is_dir())
            self.assertTrue(Path(dirs["snapshots"]).is_dir())
            self.assertTrue((Path(dirs["videos"]) / "webcam_20261024_120000.mp4").is_file())
            self.assertTrue((Path(dirs["audios"]) / "audio_20261024_120000.wav").is_file())
            self.assertTrue((Path(dirs["snapshots"]) / "snapshot_20261024_120000.jpg").is_file())

    def test_camera_stream_responder_migrates_seamlessly_on_failover(self):
        import time as _time
        instance = object.__new__(server.Server)
        instance._camera_stream_owner_lock = threading.Lock()
        wifi_responses = []
        bt_responses = []
        instance._camera_stream_owner = {
            "key": ("192.168.1.25", 53530),
            "session_id": "cam-sess-1",
            "respond": wifi_responses.append,
            "last_seen": _time.monotonic() - 2.0,
        }
        instance.live_audio_manager = Mock()

        instance._refresh_camera_stream_lease(
            ("bluetooth", "AA:BB:CC:DD:EE:FF"),
            {"type": "path_switch"},
            bt_responses.append,
        )

        self.assertEqual(instance._camera_stream_owner["key"], ("bluetooth", None))
        self.assertEqual(instance._camera_stream_owner["respond"], bt_responses.append)
        instance.live_audio_manager.touch_owner.assert_called_once()

    def test_live_audio_responder_migrates_seamlessly_on_failover(self):
        import time as _time
        with tempfile.TemporaryDirectory() as tmpdir:
            mgr = server.LiveAudioManager(tmpdir)
            wifi_resp = Mock()
            bt_resp = Mock()
            mgr.capture_running = True
            mgr.capture_owner = ("192.168.1.25", None)
            mgr.capture_respond = wifi_resp
            mgr.touch_owner(("bluetooth", None), respond=bt_resp, migrate=True)
            self.assertEqual(mgr.capture_owner, ("bluetooth", None))
            self.assertEqual(mgr.capture_respond, bt_resp)

    def test_phone_camera_rotation_and_orientation_mode(self):
        receiver = server.PhoneCameraReceiver()
        receiver.start("back", 640, 360, orientation="portrait")
        self.assertEqual(receiver.orientation, "portrait")
        self.assertEqual(receiver.rotation_degrees, 0)
        self.assertEqual(receiver.rotate_90(), 90)
        self.assertEqual(receiver.rotate_90(), 180)
        self.assertEqual(receiver.rotate_90(), 270)
        self.assertEqual(receiver.rotate_90(), 0)

    def test_qt_native_companion_architecture_present(self):
        import app_gui
        gui_source = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertEqual(app_gui.APP_VERSION, "1.5.1")
        self.assertIn("from PySide6.QtWidgets import", gui_source)
        self.assertNotIn("QTimer.singleShot(0,", gui_source)
        self.assertIn("FullscreenVideoWindow", gui_source)
        self.assertIn("AspectRatioVideoWidget", gui_source)

    def test_ott_platform_and_app_icon_identification(self):
        label, key = server.MediaController._identify_player_and_key(
            app_id="chrome.exe",
            title="Solo Leveling Episode 12",
            extra_titles="Solo Leveling - Watch on Crunchyroll - Google Chrome"
        )
        self.assertEqual(label, "Crunchyroll")
        self.assertEqual(key, "crunchyroll")

        label2, key2 = server.MediaController._identify_player_and_key(
            app_id="msedge.exe",
            title="The Boys - Season 4",
            extra_titles="Prime Video: The Boys - Microsoft Edge"
        )
        self.assertEqual(label2, "Prime Video")
        self.assertEqual(key2, "prime_video")

        label3, key3 = server.MediaController._identify_player_and_key(
            app_id="brave.exe",
            title="Stranger Things",
            extra_titles="Netflix - Brave"
        )
        self.assertEqual(label3, "Netflix")
        self.assertEqual(key3, "netflix")

        self.assertEqual(server.infer_app_brand_key("Steam", r"C:\Program Files\Steam\steam.exe"), "steam")
        self.assertEqual(server.infer_app_brand_key("Visual Studio Code", "code.exe"), "vscode")

    def test_query_app_icons_returns_batched_response(self):
        instance = object.__new__(server.Server)
        responses = []
        with patch("server.extract_windows_app_icon_base64", return_value="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="):
            instance._respond_app_icons_async(["steam", "chrome"], responses.append)
        self.assertEqual(len(responses), 1)
        self.assertEqual(responses[0]["type"], "app_icons_response")
        self.assertIn("steam", responses[0]["icons"])
        self.assertEqual(responses[0]["icons"]["steam"]["brand_key"], "steam")
        self.assertTrue(responses[0]["icons"]["steam"]["icon"])


class ClientActivityTests(unittest.TestCase):
    def test_one_link_closing_keeps_phone_connected_on_the_other(self):
        instance = object.__new__(server.Server)
        instance._note_client_activity(("192.168.1.5", 40000), "Pixel 8")
        instance._note_client_activity(("Bluetooth", 0))
        instance._note_client_activity(("Bluetooth", 0), alive=False)
        self.assertEqual(instance.active_clients(), [("Wi-Fi", "Pixel 8")])

    def test_silent_transport_expires(self):
        instance = object.__new__(server.Server)
        instance._note_client_activity(("WebSocket", 0), "Pixel 8")
        self.assertEqual(instance.active_clients(), [("Remote", "Pixel 8")])
        later = server.time.monotonic() + 30
        with patch("server.time.monotonic", return_value=later):
            self.assertEqual(instance.active_clients(), [])


class SingleInstanceTests(unittest.TestCase):
    def test_exclusive_bind_prefers_windows_exclusive_option(self):
        sock = Mock()
        with patch.object(server.socket, "SO_EXCLUSIVEADDRUSE", -5, create=True):
            server.set_exclusive_bind(sock)
        sock.setsockopt.assert_called_once_with(server.socket.SOL_SOCKET, -5, 1)

    def test_exclusive_bind_falls_back_to_reuseaddr_elsewhere(self):
        sock = Mock()
        real_socket = server.socket
        fake_socket = Mock(spec=["SOL_SOCKET", "SO_REUSEADDR"], SOL_SOCKET=real_socket.SOL_SOCKET, SO_REUSEADDR=real_socket.SO_REUSEADDR)
        with patch.object(server, "socket", fake_socket):
            server.set_exclusive_bind(sock)
        sock.setsockopt.assert_called_once_with(real_socket.SOL_SOCKET, real_socket.SO_REUSEADDR, 1)

    def test_companion_entry_point_guards_against_second_instance(self):
        gui_source = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertIn("if not acquire_single_instance_lock():", gui_source)
        self.assertIn('f"Local\\\\{SINGLE_INSTANCE_NAME}"', gui_source)


class RemoteTunnelTests(unittest.TestCase):
    """WAN rendezvous identity/record behaviour (Phase 1)."""

    def setUp(self):
        self._tmp = tempfile.mkdtemp()
        self._saved_path = remote_tunnel.REMOTE_CONFIG_PATH
        remote_tunnel.REMOTE_CONFIG_PATH = Path(self._tmp) / "remote.json"

    def tearDown(self):
        remote_tunnel.REMOTE_CONFIG_PATH = self._saved_path

    def test_record_id_is_stable_hex_across_reload(self):
        ident = remote_tunnel.RemoteIdentity()
        self.assertRegex(ident.record_id, r"^[a-f0-9]{32}$")
        # A reload must keep the same id so the printed QR stays valid across restarts.
        self.assertEqual(remote_tunnel.RemoteIdentity().record_id, ident.record_id)

    def test_worker_url_is_normalised_and_persisted(self):
        ident = remote_tunnel.RemoteIdentity()
        ident.set_worker_url("https://x.workers.dev/")
        self.assertEqual(ident.worker_url, "https://x.workers.dev")
        self.assertEqual(remote_tunnel.RemoteIdentity().worker_url, "https://x.workers.dev")

    def test_internet_access_choice_survives_restart(self):
        ident = remote_tunnel.RemoteIdentity()
        self.assertFalse(ident.tunnel_enabled)
        ident.set_tunnel_enabled(True)
        reloaded = remote_tunnel.RemoteIdentity()
        self.assertTrue(reloaded.tunnel_enabled)
        self.assertEqual(reloaded.record_id, ident.record_id)

    def test_tunnel_url_regex_matches_cloudflared_banner(self):
        line = "|  https://affect-gen-journey-speech.trycloudflare.com    |"
        match = remote_tunnel._TUNNEL_URL_RE.search(line)
        self.assertIsNotNone(match)
        self.assertEqual(
            match.group(0).replace("https://", "wss://", 1),
            "wss://affect-gen-journey-speech.trycloudflare.com",
        )

    def test_publish_sends_named_user_agent(self):
        # Cloudflare answers urllib's default agent with error 1010 before the Worker runs.
        ident = remote_tunnel.RemoteIdentity()
        ident.set_worker_url("https://x.workers.dev")
        publisher = remote_tunnel.TunnelPublisher(ident, 53531, "PC")
        response = MagicMock(status=200)
        response.__enter__.return_value = response
        with patch.object(remote_tunnel.urllib.request, "urlopen", return_value=response) as urlopen:
            publisher._publish("wss://x.trycloudflare.com")
        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_method(), "PUT")
        self.assertEqual(request.get_header("User-agent"), remote_tunnel.USER_AGENT)

    @unittest.skipUnless(remote_tunnel.HAS_NACL, "PyNaCl not installed")
    def test_signed_record_round_trips_and_rejects_tamper(self):
        from nacl.signing import VerifyKey

        ident = remote_tunnel.RemoteIdentity()
        self.assertEqual(len(base64.b64decode(ident.public_b64)), 32)
        record = json.dumps(
            {"v": 1, "url": "wss://x.trycloudflare.com", "ts": 1, "host": "PC"},
            separators=(",", ":"),
        )
        sig = ident.sign_b64(record.encode())
        self.assertEqual(len(base64.b64decode(sig)), 64)
        VerifyKey(base64.b64decode(ident.public_b64)).verify(record.encode(), base64.b64decode(sig))
        tampered = bytearray(base64.b64decode(sig))
        tampered[0] ^= 1
        with self.assertRaises(Exception):
            VerifyKey(base64.b64decode(ident.public_b64)).verify(record.encode(), bytes(tampered))


class CompanionUpdateScriptTests(unittest.TestCase):
    """The EXE swap must outlive the one-file launcher, which keeps the EXE locked briefly."""

    def build(self, pids=(111, 222)):
        return update_script.build_update_script(
            wait_pids=pids, target=r"C:\Users\O'Neil\Desktop\BT-Mouse-Companion.exe",
            staged=r"C:\t\new.exe", replacement=r"C:\t\.new.exe", backup=r"C:\t\.backup.exe",
            ready_marker=r"C:\t\ready.txt", error_log=r"C:\t\err.log", step_log=r"C:\t\steps.log",
            expected_version="1.4.3",
        )

    def test_waits_for_every_process_holding_the_exe(self):
        script = self.build()
        self.assertIn("$pidsToWait = @(111, 222)", script)
        self.assertIn("foreach ($p in $pidsToWait)", script)

    def test_retries_while_the_exe_is_still_locked(self):
        script = self.build()
        self.assertIn("Retry { Move-Item -LiteralPath $target -Destination $backup -Force } 'Moving the old version aside' 60", script)

    def test_keeps_a_slow_starting_new_version_instead_of_rolling_back(self):
        script = self.build()
        self.assertIn(f"AddSeconds({update_script.LAUNCH_ACK_SECONDS})", script)
        self.assertGreaterEqual(update_script.LAUNCH_ACK_SECONDS, 60)
        self.assertIn("elseif (-not $started.HasExited)", script)

    def test_logs_steps_and_quotes_paths(self):
        script = self.build()
        self.assertIn("$stepLog = 'C:\\t\\steps.log'", script)
        self.assertIn("Log 'Update finished'", script)
        self.assertIn("'C:\\Users\\O''Neil\\Desktop\\BT-Mouse-Companion.exe'", script)

    def test_companion_uses_shared_script_and_shows_progress(self):
        gui = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertIn("build_update_script(", gui)
        self.assertIn("wait_pids=companion_update_wait_pids()", gui)
        self.assertIn("QProgressDialog(", gui)
        self.assertIn('encoding="utf-8-sig"', gui)


class WanQrWiringTests(unittest.TestCase):
    def test_wan_qr_includes_rendezvous_block_and_tunnel_controls(self):
        gui = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertIn("from remote_tunnel import RemoteIdentity, TunnelPublisher", gui)
        self.assertIn('payload["rv"]', gui)
        self.assertIn('"worker": self.remote_identity.worker_url', gui)
        self.assertIn("def _toggle_tunnel", gui)

    def test_one_pairing_qr_carries_every_link(self):
        gui = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertIn('"type": "btmouse_pc"', gui)
        for field in ('"ip": self.current_ip', '"port": self.current_port', '"ws_port": self.ws_port',
                      '"name": socket.gethostname()'):
            self.assertIn(field, gui)
        self.assertIn("self._set_qr_pixmap(self.qr_label, self.pairing_payload()", gui)

    def test_one_pair_page_replaces_separate_connection_pages(self):
        gui = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertIn('("Connect", "link", self._create_pair_page)', gui)
        for removed in ("_create_wifi_page", "_create_bt_page", "_create_wan_page", "wan_qr_label"):
            self.assertNotIn(removed, gui)
        self.assertIn("QTimer.singleShot(1500, self._restore_tunnel)", gui)


class CompanionLayoutTests(unittest.TestCase):
    def test_sidebar_pages_and_no_drawing_slate(self):
        gui = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        for page in ('("Phone camera", "camera", self._create_phone_cam_page)',
                     '("Webcam && apps", "video", self._create_desktop_page)',
                     '("Library", "library", self._create_recordings_page)',
                     '("Activity", "pulse", self._create_logs_page)',
                     '("Settings", "settings", self._create_settings_page)'):
            self.assertIn(page, gui)
        for removed in ("_create_tablet_page", "Drawing slate", "open_paint"):
            self.assertNotIn(removed, gui)

    def test_screen_sharing_banner_and_setting_are_wired(self):
        gui = (Path(__file__).parent / "app_gui.py").read_text(encoding="utf-8")
        self.assertIn("self.server.stop_screen_sharing()", gui)
        self.assertIn("self.server.set_screen_preview_allowed(allowed)", gui)
        self.assertIn("self._refresh_screen_banner()", gui)

    def test_bundled_inter_fonts_ship_with_the_companion(self):
        here = Path(__file__).parent
        spec = (here / "BT-Mouse-Companion.spec").read_text(encoding="utf-8")
        for name in ("inter_regular.ttf", "inter_medium.ttf", "inter_semibold.ttf"):
            self.assertTrue((here / "fonts" / name).is_file(), name)
            self.assertIn(f"fonts/{name}", spec)


class ScreenPreviewTests(unittest.TestCase):
    TOKEN = "123456"

    def make_server(self):
        instance = object.__new__(server.Server)
        instance.auth_token = self.TOKEN
        instance.auth_failures = {}
        instance.connected_client = None
        instance.on_packet_callback = None
        instance._camera_stream_owner_lock = threading.Lock()
        instance.live_audio_manager = Mock()
        instance.mouse = SimpleNamespace(position=(40, 30))
        return instance

    def query(self, instance, **extra):
        responses = []
        with patch.object(server, "capture_screen_preview", return_value=("QUJD", 960, 540)) as capture, \
                patch.object(server.threading, "Thread", side_effect=lambda target, **_: SimpleNamespace(start=target)):
            instance.handle_packet(dict({"type": "screen_query", "token": self.TOKEN}, **extra),
                                   ("127.0.0.1", 9999), responses.append)
        return responses, capture

    def test_screen_query_returns_frame_with_pointer_position(self):
        instance = self.make_server()
        responses, capture = self.query(instance, max_width=640, max_bytes=30000)
        self.assertEqual(responses, [{"type": "screen_frame", "frame": "QUJD", "width": 960, "height": 540}])
        capture.assert_called_once_with((40, 30), 640, 30000)
        self.assertTrue(instance.screen_viewer_active())

    def test_screen_query_refused_when_preview_is_off(self):
        instance = self.make_server()
        instance.screen_preview_allowed = False
        responses, capture = self.query(instance)
        capture.assert_not_called()
        self.assertIn("turned off", responses[0]["error"])

    def test_stop_sharing_holds_until_phone_resumes(self):
        instance = self.make_server()
        instance.stop_screen_sharing()
        self.assertFalse(instance.screen_viewer_active())
        responses, capture = self.query(instance)
        capture.assert_not_called()
        self.assertIn("stopped", responses[0]["error"])
        responses, capture = self.query(instance, resume=True)
        self.assertEqual(responses[0]["frame"], "QUJD")

    def test_unauthorized_screen_query_captures_nothing(self):
        instance = self.make_server()
        responses = []
        with patch.object(server, "capture_screen_preview") as capture:
            instance.handle_packet({"type": "screen_query", "token": "000000"}, ("127.0.0.1", 9999), responses.append)
        capture.assert_not_called()

    def test_encoded_frame_fits_one_datagram(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow not installed")
        noisy = Image.effect_noise((1920, 1080), 90).convert("RGB")
        frame, width, height = server.encode_screen_preview(noisy, cursor=(100, 100), max_width=1920, max_base64=20000)
        self.assertLessEqual(len(frame), 20000)
        self.assertLessEqual(width, 1920)
        self.assertEqual(round(width * 1080 / 1920), height)
        payload = json.dumps({"type": "screen_frame", "frame": "x" * server.SCREEN_FRAME_MAX_BASE64, "width": 9999, "height": 9999})
        self.assertLess(len(payload), 60000)


if __name__ == "__main__":
    unittest.main()
