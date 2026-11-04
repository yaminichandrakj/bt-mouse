import unittest
from pathlib import Path
import tempfile
from PySide6.QtWidgets import QApplication
from PySide6.QtCore import QSize
from PySide6.QtGui import QImage

import gallery_view

app = QApplication.instance() or QApplication([])


class GalleryViewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def test_thumbnail_cache_singleton(self):
        c1 = gallery_view.ThumbnailCache.get()
        c2 = gallery_view.ThumbnailCache.get()
        self.assertIs(c1, c2)

    def test_thumbnail_generation_for_image(self):
        img_path = Path(self.tmp.name) / "test_photo.png"
        img = QImage(300, 200, QImage.Format.Format_RGB32)
        img.fill(0xFF00FF)
        self.assertTrue(img.save(str(img_path)))

        cache = gallery_view.ThumbnailCache.get()
        pix = cache.get_thumbnail(str(img_path), QSize(100, 100))
        self.assertIsNotNone(pix)
        self.assertFalse(pix.isNull())

    def test_gallery_hub_record_filtering(self):
        records = [
            {"name": "photo1.jpg", "path": None, "remote": ("dev1", "photo1.jpg"), "device_id": "dev1", "device_name": "Pixel 8",
             "is_snapshot": True, "is_video": False, "is_audio": False, "size": 1024, "size_str": "1 KB", "mtime": 100, "date_str": "2026-11-04", "folder": "Snapshots"},
            {"name": "video1.mp4", "path": "/tmp/video1.mp4", "remote": None, "device_id": "pc", "device_name": "This PC",
             "is_snapshot": False, "is_video": True, "is_audio": False, "size": 2048, "size_str": "2 KB", "mtime": 200, "date_str": "2026-11-04", "folder": "Videos"},
            {"name": "audio1.wav", "path": "/tmp/audio1.wav", "remote": None, "device_id": "pc", "device_name": "This PC",
             "is_snapshot": False, "is_video": False, "is_audio": True, "size": 512, "size_str": "512 B", "mtime": 300, "date_str": "2026-11-04", "folder": "Audios"}
        ]

        class DummyCompanion:
            pass

        hub = gallery_view.CrossDeviceGalleryHub(DummyCompanion())
        hub.update_records(records)

        # All records
        hub.set_category("all")
        self.assertEqual(len(hub.current_records), 3)

        # Filter photo
        hub.set_category("snapshot")
        self.assertEqual(len(hub.current_records), 1)
        self.assertEqual(hub.current_records[0]["name"], "photo1.jpg")

        # Filter video
        hub.set_category("video")
        self.assertEqual(len(hub.current_records), 1)
        self.assertEqual(hub.current_records[0]["name"], "video1.mp4")

        # Search
        hub.set_category("all")
        hub.ent_search.setText("audio")
        self.assertEqual(len(hub.current_records), 1)
        self.assertEqual(hub.current_records[0]["name"], "audio1.wav")


if __name__ == "__main__":
    unittest.main()

