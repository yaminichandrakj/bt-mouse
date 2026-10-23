"""Hardware-free regression checks for the companion command protocol."""

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

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


if __name__ == "__main__":
    unittest.main()
