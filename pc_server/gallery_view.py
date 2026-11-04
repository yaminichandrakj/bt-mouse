"""Lenovo Smart Connect-inspired Cross-Device Gallery and Media Hub.
Provides visual thumbnail grids, device filtering, search, and a full lightbox viewer.
"""

import os
import subprocess
import threading
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import QPoint, QRect, QRectF, QSize, Qt, QTimer, Signal
from PySide6.QtGui import (
    QAction,
    QBrush,
    QColor,
    QCursor,
    QFont,
    QIcon,
    QImage,
    QImageReader,
    QKeySequence,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
    QShortcut,
)
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QComboBox,
    QDialog,
    QFileDialog,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QStackedWidget,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)


class ThumbnailCache:
    """Thread-safe thumbnail generator and memory cache for images and videos."""

    _instance = None
    _lock = threading.Lock()

    def __init__(self, max_items=600):
        self._cache = {}
        self._pending = set()
        self._max_items = max_items
        self._q_lock = threading.Lock()

    @classmethod
    def get(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def get_thumbnail(self, path_str, size=QSize(220, 140)):
        if not path_str:
            return None
        key = (path_str, size.width(), size.height())
        with self._q_lock:
            if key in self._cache:
                return self._cache[key]

        # Generate on demand
        pix = self._generate(path_str, size)
        if pix and not pix.isNull():
            with self._q_lock:
                if len(self._cache) >= self._max_items:
                    # Evict some entries
                    keys = list(self._cache.keys())[:100]
                    for k in keys:
                        self._cache.pop(k, None)
                self._cache[key] = pix
            return pix
        return None

    def _generate(self, path_str, size):
        path = Path(path_str)
        if not path.is_file():
            return None
        ext = path.suffix.lower()

        # Image generation
        if ext in {".jpg", ".jpeg", ".png", ".webp", ".bmp"}:
            try:
                reader = QImageReader(str(path))
                reader.setAutoTransform(True)
                orig_size = reader.size()
                if orig_size.isValid() and not orig_size.isEmpty():
                    scaled_size = orig_size.scaled(size, Qt.AspectRatioMode.KeepAspectRatioByExpanding)
                    reader.setScaledSize(scaled_size)
                img = reader.read()
                if not img.isNull():
                    pix = QPixmap.fromImage(img)
                    return pix.scaled(size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            except Exception:
                pass

        # Video thumbnail generation via OpenCV if available
        if ext in {".mp4", ".mkv", ".avi", ".webm", ".mov"}:
            try:
                import cv2
                cap = cv2.VideoCapture(str(path))
                if cap.isOpened():
                    # Read frame at ~1s
                    fps = cap.get(cv2.CAP_PROP_FPS) or 24.0
                    cap.set(cv2.CAP_PROP_POS_FRAMES, int(fps * 0.8))
                    ret, frame = cap.read()
                    if not ret:
                        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                        ret, frame = cap.read()
                    cap.release()
                    if ret and frame is not None:
                        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                        h, w, ch = rgb.shape
                        qimg = QImage(rgb.data, w, h, ch * w, QImage.Format.Format_RGB888)
                        pix = QPixmap.fromImage(qimg.copy())
                        return pix.scaled(size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            except Exception:
                pass

        return None


class MediaViewerModal(QDialog):
    """Full-screen lightbox viewer for photos, videos, and snapshots.
    Supports zooming, pan, next/previous keyboard navigation, and downloading remote items.
    """

    def __init__(self, records, current_index, companion_app, parent=None):
        super().__init__(parent or companion_app, Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint)
        self.records = records
        self.index = current_index
        self.app = companion_app
        self.setMinimumSize(880, 620)
        self.resize(1100, 780)
        self.setStyleSheet("background-color: #0E0F12; color: #E8E8E8; border: 1px solid #2B2F38; border-radius: 14px;")

        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(16, 14, 16, 16)
        main_lay.setSpacing(12)

        # Top Bar
        top_bar = QHBoxLayout()
        top_bar.setSpacing(12)

        self.lbl_title = QLabel()
        self.lbl_title.setStyleSheet("font-size: 16px; font-weight: 600; color: #FFFFFF;")
        top_bar.addWidget(self.lbl_title)

        self.pill_device = QLabel()
        self.pill_device.setStyleSheet("background-color: #1A365D; color: #63B3ED; border: 1px solid #3182CE; "
                                       "border-radius: 12px; padding: 3px 10px; font-size: 12px; font-weight: 500;")
        top_bar.addWidget(self.pill_device)

        self.lbl_meta = QLabel()
        self.lbl_meta.setStyleSheet("color: #A0AEC0; font-size: 13px; margin-left: 6px;")
        top_bar.addWidget(self.lbl_meta)

        top_bar.addStretch()

        self.lbl_counter = QLabel()
        self.lbl_counter.setStyleSheet("color: #718096; font-size: 13px;")
        top_bar.addWidget(self.lbl_counter)

        btn_close = QPushButton("✕")
        btn_close.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        btn_close.setStyleSheet("QPushButton { background-color: #1A1D24; color: #E2E8F0; border: 1px solid #2D3748; "
                                "border-radius: 16px; font-size: 14px; width: 32px; height: 32px; font-weight: bold; } "
                                "QPushButton:hover { background-color: #E53E3E; color: white; border-color: #E53E3E; }")
        btn_close.clicked.connect(self.accept)
        top_bar.addWidget(btn_close)
        main_lay.addLayout(top_bar)

        # Center Content View
        content_box = QHBoxLayout()
        content_box.setSpacing(8)

        self.btn_prev = QPushButton("◀")
        self.btn_prev.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_prev.setStyleSheet("QPushButton { background-color: rgba(26, 29, 36, 0.75); color: #E2E8F0; "
                                    "border: 1px solid #2D3748; border-radius: 22px; font-size: 16px; width: 44px; height: 44px; } "
                                    "QPushButton:hover { background-color: #3182CE; color: white; border-color: #3182CE; }")
        self.btn_prev.clicked.connect(self.prev_item)
        content_box.addWidget(self.btn_prev)

        self.preview_label = QLabel("Loading preview…")
        self.preview_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview_label.setStyleSheet("background-color: #050608; border-radius: 10px; border: 1px solid #1E222A;")
        self.preview_label.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        content_box.addWidget(self.preview_label, 1)

        self.btn_next = QPushButton("▶")
        self.btn_next.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_next.setStyleSheet("QPushButton { background-color: rgba(26, 29, 36, 0.75); color: #E2E8F0; "
                                    "border: 1px solid #2D3748; border-radius: 22px; font-size: 16px; width: 44px; height: 44px; } "
                                    "QPushButton:hover { background-color: #3182CE; color: white; border-color: #3182CE; }")
        self.btn_next.clicked.connect(self.next_item)
        content_box.addWidget(self.btn_next)

        main_lay.addLayout(content_box, 1)

        # Bottom Action Bar
        act_bar = QHBoxLayout()
        act_bar.setSpacing(10)

        self.btn_download = QPushButton("📥 Download to PC")
        self.btn_download.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_download.setStyleSheet("QPushButton { background-color: #3182CE; color: white; border: none; "
                                        "border-radius: 8px; padding: 8px 18px; font-weight: 600; font-size: 13px; } "
                                        "QPushButton:hover { background-color: #2B6CB0; }")
        self.btn_download.clicked.connect(self._download_current)
        act_bar.addWidget(self.btn_download)

        self.btn_open = QPushButton("▶ Open in App")
        self.btn_open.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_open.setStyleSheet("QPushButton { background-color: #2D3748; color: #EDF2F7; border: 1px solid #4A5568; "
                                    "border-radius: 8px; padding: 8px 16px; font-weight: 500; font-size: 13px; } "
                                    "QPushButton:hover { background-color: #4A5568; }")
        self.btn_open.clicked.connect(self._open_current)
        act_bar.addWidget(self.btn_open)

        self.btn_explorer = QPushButton("📁 Show in Explorer")
        self.btn_explorer.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_explorer.setStyleSheet("QPushButton { background-color: #2D3748; color: #EDF2F7; border: 1px solid #4A5568; "
                                        "border-radius: 8px; padding: 8px 16px; font-weight: 500; font-size: 13px; } "
                                        "QPushButton:hover { background-color: #4A5568; }")
        self.btn_explorer.clicked.connect(self._show_in_explorer)
        act_bar.addWidget(self.btn_explorer)

        self.btn_copy_image = QPushButton("📋 Copy Image")
        self.btn_copy_image.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_copy_image.setStyleSheet("QPushButton { background-color: #2D3748; color: #EDF2F7; border: 1px solid #4A5568; "
                                          "border-radius: 8px; padding: 8px 16px; font-weight: 500; font-size: 13px; } "
                                          "QPushButton:hover { background-color: #4A5568; }")
        self.btn_copy_image.clicked.connect(self._copy_image)
        act_bar.addWidget(self.btn_copy_image)

        act_bar.addStretch()

        self.lbl_status = QLabel("")
        self.lbl_status.setStyleSheet("color: #63B3ED; font-size: 13px; font-weight: 500;")
        act_bar.addWidget(self.lbl_status)

        main_lay.addLayout(act_bar)

        # Shortcuts
        QShortcut(QKeySequence(Qt.Key.Key_Left), self, self.prev_item)
        QShortcut(QKeySequence(Qt.Key.Key_Right), self, self.next_item)
        QShortcut(QKeySequence(Qt.Key.Key_Escape), self, self.accept)

        self._render_current()

    def _current_rec(self):
        if 0 <= self.index < len(self.records):
            return self.records[self.index]
        return None

    def prev_item(self):
        if self.index > 0:
            self.index -= 1
            self._render_current()

    def next_item(self):
        if self.index < len(self.records) - 1:
            self.index += 1
            self._render_current()

    def _render_current(self):
        rec = self._current_rec()
        if not rec:
            return

        self.lbl_counter.setText(f"{self.index + 1} of {len(self.records)}")
        self.btn_prev.setEnabled(self.index > 0)
        self.btn_next.setEnabled(self.index < len(self.records) - 1)

        name = rec.get("name", "Media")
        self.lbl_title.setText(name)
        dev_name = rec.get("device_name") or ("This PC" if not rec.get("remote") else rec.get("folder", "Device"))
        if dev_name.startswith("On "):
            dev_name = dev_name[3:]
        self.pill_device.setText(f"📱 {dev_name}" if rec.get("remote") else f"💻 {dev_name}")

        size_str = rec.get("size_str", "")
        date_str = rec.get("date_str", "")
        type_name = rec.get("type_name", "Media")
        self.lbl_meta.setText(f"{type_name}  ·  {size_str}  ·  {date_str}")

        is_remote = bool(rec.get("remote"))
        self.btn_download.setVisible(is_remote)
        self.btn_open.setVisible(not is_remote)
        self.btn_explorer.setVisible(not is_remote)
        self.btn_copy_image.setVisible(rec.get("is_snapshot") or name.lower().endswith((".jpg", ".jpeg", ".png", ".webp")))

        path = rec.get("path")
        if path and Path(path).is_file():
            # Local item
            pix = QPixmap(str(path))
            if not pix.isNull():
                scaled = pix.scaled(self.preview_label.size() - QSize(20, 20),
                                    Qt.AspectRatioMode.KeepAspectRatio,
                                    Qt.TransformationMode.SmoothTransformation)
                self.preview_label.setPixmap(scaled)
            else:
                self.preview_label.setPixmap(QPixmap())
                self.preview_label.setText(f"▶ {name}\n\nLocal media file ready to play.")
        else:
            # Remote item
            self.preview_label.setPixmap(QPixmap())
            icon = "📷" if rec.get("is_snapshot") else ("🎬" if rec.get("is_video") else "🎵")
            self.preview_label.setText(f"{icon}\n\n{name}\n\nStored on {dev_name}.\nClick 'Download to PC' to fetch at full quality.")

    def resizeEvent(self, event):
        super().resizeEvent(event)
        rec = self._current_rec()
        if rec and rec.get("path") and Path(rec["path"]).is_file():
            pix = QPixmap(str(rec["path"]))
            if not pix.isNull():
                scaled = pix.scaled(self.preview_label.size() - QSize(20, 20),
                                    Qt.AspectRatioMode.KeepAspectRatio,
                                    Qt.TransformationMode.SmoothTransformation)
                self.preview_label.setPixmap(scaled)

    def _download_current(self):
        rec = self._current_rec()
        if not rec or not rec.get("remote"):
            return
        device_id, item_id = rec["remote"]
        eco = getattr(self.app.server, "ecosystem", None) if self.app.server else None
        if not eco or not hasattr(eco, "library"):
            self.lbl_status.setText("PC Ecosystem is not running.")
            return

        self.lbl_status.setText("Downloading from device…")
        if eco.library.fetch(device_id, item_id):
            self.lbl_status.setText("Transfer initiated! It will appear locally once received.")
            # Trigger refresh after a moment
            QTimer.singleShot(2500, self.app.refresh_recordings)
        else:
            self.lbl_status.setText("Failed to request file from device.")

    def _open_current(self):
        rec = self._current_rec()
        if rec and rec.get("path"):
            path = str(rec["path"])
            if os.name == "nt":
                os.startfile(path)  # noqa: S606
            else:
                subprocess.Popen(["xdg-open", path])

    def _show_in_explorer(self):
        rec = self._current_rec()
        if rec and rec.get("path"):
            path = str(Path(rec["path"]).resolve())
            if os.name == "nt":
                subprocess.Popen(["explorer.exe", "/select,", path])
            else:
                subprocess.Popen(["xdg-open", str(Path(path).parent)])

    def _copy_image(self):
        rec = self._current_rec()
        if rec and rec.get("path") and Path(rec["path"]).is_file():
            pix = QPixmap(str(rec["path"]))
            if not pix.isNull():
                QApplication.clipboard().setPixmap(pix)
                self.lbl_status.setText("Image copied to clipboard!")


class MediaCard(QFrame):
    """Modern Lenovo Smart Connect visual card showing a photo/video thumbnail,
    origin device badge, file details, and hover actions."""

    clicked = Signal(dict)
    double_clicked = Signal(dict)
    download_requested = Signal(dict)

    def __init__(self, record, companion_app, parent=None):
        super().__init__(parent)
        self.record = record
        self.app = companion_app
        self.setFixedSize(220, 200)
        self.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.setObjectName("MediaCard")
        self._is_hovered = False

        self.setStyleSheet("""
            QFrame#MediaCard {
                background-color: #16181D;
                border: 1px solid #282C35;
                border-radius: 12px;
            }
            QFrame#MediaCard:hover {
                border: 1.5px solid #5AAAFF;
                background-color: #1A1E26;
            }
        """)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(6)

        # Thumbnail Container
        self.thumb_box = QFrame()
        self.thumb_box.setFixedHeight(120)
        self.thumb_box.setStyleSheet("background-color: #0E1015; border-radius: 8px;")
        t_lay = QVBoxLayout(self.thumb_box)
        t_lay.setContentsMargins(6, 6, 6, 6)

        # Top Badge Row over thumbnail
        badge_row = QHBoxLayout()
        badge_row.setContentsMargins(0, 0, 0, 0)

        # Source device badge
        is_remote = bool(record.get("remote"))
        dev_name = record.get("device_name") or ("This PC" if not is_remote else record.get("folder", "Device"))
        if dev_name.startswith("On "):
            dev_name = dev_name[3:]

        self.badge_dev = QLabel(f"📱 {dev_name}" if is_remote else "💻 PC")
        if is_remote:
            self.badge_dev.setStyleSheet("background-color: rgba(14, 68, 140, 0.9); color: #90CDF4; "
                                         "border-radius: 6px; padding: 2px 6px; font-size: 11px; font-weight: 600;")
        else:
            self.badge_dev.setStyleSheet("background-color: rgba(45, 55, 72, 0.9); color: #CBD5E0; "
                                         "border-radius: 6px; padding: 2px 6px; font-size: 11px; font-weight: 500;")
        badge_row.addWidget(self.badge_dev)
        badge_row.addStretch()

        # Type tag badge
        type_label = "PHOTO" if record.get("is_snapshot") else ("VIDEO" if record.get("is_video") else "AUDIO")
        self.badge_type = QLabel(type_label)
        self.badge_type.setStyleSheet("background-color: rgba(0, 0, 0, 0.65); color: #E2E8F0; "
                                      "border-radius: 6px; padding: 2px 6px; font-size: 10px; font-weight: 600;")
        badge_row.addWidget(self.badge_type)
        t_lay.addLayout(badge_row)

        # Center thumbnail image
        self.lbl_img = QLabel()
        self.lbl_img.setAlignment(Qt.AlignmentFlag.AlignCenter)
        t_lay.addWidget(self.lbl_img, 1)

        layout.addWidget(self.thumb_box)

        # Bottom Info
        self.lbl_name = QLabel(record.get("name", "Media"))
        self.lbl_name.setStyleSheet("color: #F7FAFC; font-size: 12px; font-weight: 600;")
        self.lbl_name.setToolTip(record.get("name", ""))
        font = self.lbl_name.font()
        font_metrics = self.fontMetrics()
        self.lbl_name.setText(font_metrics.elidedText(record.get("name", ""), Qt.TextElideMode.ElideMiddle, 200))
        layout.addWidget(self.lbl_name)

        # Size and date
        sub_text = f"{record.get('size_str', '')}  ·  {record.get('date_str', '').split(' ')[0]}"
        self.lbl_sub = QLabel(sub_text)
        self.lbl_sub.setStyleSheet("color: #718096; font-size: 11px;")
        layout.addWidget(self.lbl_sub)

        self._load_thumbnail()

    def _load_thumbnail(self):
        rec = self.record
        path = rec.get("path")
        if path and Path(path).is_file():
            # Check cache
            pix = ThumbnailCache.get().get_thumbnail(str(path), QSize(204, 88))
            if pix and not pix.isNull():
                self.lbl_img.setPixmap(pix)
                return

        # Fallback icon
        if rec.get("is_snapshot"):
            self.lbl_img.setText("📷")
            self.lbl_img.setStyleSheet("font-size: 26px; color: #4A5568;")
        elif rec.get("is_video"):
            self.lbl_img.setText("🎬")
            self.lbl_img.setStyleSheet("font-size: 26px; color: #4A5568;")
        else:
            self.lbl_img.setText("🎵")
            self.lbl_img.setStyleSheet("font-size: 26px; color: #4A5568;")

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit(self.record)
        super().mousePressEvent(event)

    def mouseDoubleClickEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.double_clicked.emit(self.record)
        super().mouseDoubleClickEvent(event)


class CrossDeviceGalleryHub(QWidget):
    """Smart Gallery & Media Hub with view switcher (Grid/List),
    device selector chips, search, sort, and lightbox integration."""

    def __init__(self, companion_app, parent=None):
        super().__init__(parent)
        self.app = companion_app
        self.view_mode = "grid"   # "grid" or "list"
        self.filter_category = "all"
        self.filter_device = "all"
        self.current_records = []

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(12)

        # Toolbar
        tool_frame = QFrame()
        tool_frame.setObjectName("SubCard")
        tool_lay = QHBoxLayout(tool_frame)
        tool_lay.setContentsMargins(12, 10, 12, 10)
        tool_lay.setSpacing(10)

        # View mode toggle buttons
        self.btn_grid = QPushButton("⊞ Grid")
        self.btn_grid.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_grid.setStyleSheet("QPushButton { background-color: #3182CE; color: white; border: none; "
                                    "border-radius: 6px; padding: 6px 14px; font-weight: 600; font-size: 13px; }")
        self.btn_grid.clicked.connect(lambda: self.set_view_mode("grid"))
        tool_lay.addWidget(self.btn_grid)

        self.btn_list = QPushButton("☰ List")
        self.btn_list.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.btn_list.setStyleSheet("QPushButton { background-color: #1A202C; color: #A0AEC0; border: 1px solid #2D3748; "
                                    "border-radius: 6px; padding: 6px 14px; font-weight: 500; font-size: 13px; } "
                                    "QPushButton:hover { background-color: #2D3748; color: white; }")
        self.btn_list.clicked.connect(lambda: self.set_view_mode("list"))
        tool_lay.addWidget(self.btn_list)

        # Device selector
        tool_lay.addWidget(QLabel("Device:"))
        self.combo_device = QComboBox()
        self.combo_device.addItem("All Devices", "all")
        self.combo_device.addItem("💻 This PC", "pc")
        self.combo_device.currentIndexChanged.connect(self._on_filter_changed)
        tool_lay.addWidget(self.combo_device)

        # Category buttons
        self.btn_cat_all = QPushButton("All")
        self.btn_cat_photos = QPushButton("Photos")
        self.btn_cat_videos = QPushButton("Videos")
        self.btn_cat_audios = QPushButton("Audios")

        for cat, btn in [("all", self.btn_cat_all), ("snapshot", self.btn_cat_photos),
                         ("video", self.btn_cat_videos), ("audio", self.btn_cat_audios)]:
            btn.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
            btn.clicked.connect(lambda _c=False, c=cat: self.set_category(c))
            tool_lay.addWidget(btn)

        tool_lay.addStretch()

        # Search bar
        self.ent_search = QLineEdit()
        self.ent_search.setPlaceholderText("Search media by name…")
        self.ent_search.setFixedWidth(200)
        self.ent_search.textChanged.connect(self._on_filter_changed)
        tool_lay.addWidget(self.ent_search)

        # Sort combo
        self.combo_sort = QComboBox()
        self.combo_sort.addItems(["Newest First", "Oldest First", "Name (A-Z)", "Size (Largest)"])
        self.combo_sort.currentIndexChanged.connect(self._on_filter_changed)
        tool_lay.addWidget(self.combo_sort)

        lay.addWidget(tool_frame)

        # Stack for Grid vs List
        self.stack = QStackedWidget()

        # Grid View (Page 0)
        self.scroll_grid = QScrollArea()
        self.scroll_grid.setWidgetResizable(True)
        self.scroll_grid.setStyleSheet("QScrollArea { border: none; background: transparent; }")
        self.grid_container = QWidget()
        self.grid_container.setStyleSheet("background: transparent;")
        self.grid_layout = QGridLayout(self.grid_container)
        self.grid_layout.setContentsMargins(4, 4, 4, 4)
        self.grid_layout.setSpacing(14)
        self.scroll_grid.setWidget(self.grid_container)
        self.stack.addWidget(self.scroll_grid)

        # List View (Page 1)
        self.table_list = QTableWidget(0, 5)
        self.table_list.setHorizontalHeaderLabels(["Name", "Device / Folder", "Type", "Created", "Size"])
        header = self.table_list.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)
        self.table_list.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table_list.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table_list.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table_list.verticalHeader().setVisible(False)
        self.table_list.itemDoubleClicked.connect(self._on_table_double_clicked)
        self.stack.addWidget(self.table_list)

        lay.addWidget(self.stack, 1)
        self._update_category_buttons()

    def set_view_mode(self, mode):
        self.view_mode = mode
        if mode == "grid":
            self.stack.setCurrentIndex(0)
            self.btn_grid.setStyleSheet("QPushButton { background-color: #3182CE; color: white; border: none; "
                                        "border-radius: 6px; padding: 6px 14px; font-weight: 600; font-size: 13px; }")
            self.btn_list.setStyleSheet("QPushButton { background-color: #1A202C; color: #A0AEC0; border: 1px solid #2D3748; "
                                        "border-radius: 6px; padding: 6px 14px; font-weight: 500; font-size: 13px; } "
                                        "QPushButton:hover { background-color: #2D3748; color: white; }")
        else:
            self.stack.setCurrentIndex(1)
            self.btn_list.setStyleSheet("QPushButton { background-color: #3182CE; color: white; border: none; "
                                        "border-radius: 6px; padding: 6px 14px; font-weight: 600; font-size: 13px; }")
            self.btn_grid.setStyleSheet("QPushButton { background-color: #1A202C; color: #A0AEC0; border: 1px solid #2D3748; "
                                        "border-radius: 6px; padding: 6px 14px; font-weight: 500; font-size: 13px; } "
                                        "QPushButton:hover { background-color: #2D3748; color: white; }")
        self.render()

    def set_category(self, cat):
        self.filter_category = cat
        self._update_category_buttons()
        self._on_filter_changed()

    def _update_category_buttons(self):
        for c, btn in [("all", self.btn_cat_all), ("snapshot", self.btn_cat_photos),
                       ("video", self.btn_cat_videos), ("audio", self.btn_cat_audios)]:
            if self.filter_category == c:
                btn.setStyleSheet("QPushButton { background-color: #2B6CB0; color: white; border: none; "
                                  "border-radius: 6px; padding: 6px 12px; font-weight: 600; font-size: 12px; }")
            else:
                btn.setStyleSheet("QPushButton { background-color: #1A202C; color: #A0AEC0; border: 1px solid #2D3748; "
                                  "border-radius: 6px; padding: 6px 12px; font-size: 12px; } "
                                  "QPushButton:hover { background-color: #2D3748; color: white; }")

    def sync_devices(self, devices):
        """Update the device filter dropdown with connected devices."""
        current_data = self.combo_device.currentData()
        self.combo_device.blockSignals(True)
        self.combo_device.clear()
        self.combo_device.addItem("All Devices", "all")
        self.combo_device.addItem("💻 This PC", "pc")
        for dev in devices:
            self.combo_device.addItem(f"📱 {dev.get('name', 'Device')}", dev["device_id"])
        idx = self.combo_device.findData(current_data)
        if idx >= 0:
            self.combo_device.setCurrentIndex(idx)
        self.combo_device.blockSignals(False)

    def _on_filter_changed(self):
        self.render()

    def update_records(self, all_records):
        self.all_records = all_records
        self.render()

    def render(self):
        items = list(getattr(self, "all_records", []))

        # Filter by category
        if self.filter_category == "snapshot":
            items = [x for x in items if x.get("is_snapshot")]
        elif self.filter_category == "video":
            items = [x for x in items if x.get("is_video")]
        elif self.filter_category == "audio":
            items = [x for x in items if x.get("is_audio")]

        # Filter by device
        dev_filter = self.combo_device.currentData()
        if dev_filter == "pc":
            items = [x for x in items if not x.get("remote")]
        elif dev_filter and dev_filter != "all":
            items = [x for x in items if x.get("device_id") == dev_filter]

        # Filter by search
        q = self.ent_search.text().strip().lower()
        if q:
            items = [x for x in items if q in x["name"].lower() or q in x["folder"].lower() or q in x.get("date_str", "").lower()]

        # Sort
        sort = self.combo_sort.currentText()
        if sort == "Newest First":
            items.sort(key=lambda x: x.get("mtime", 0), reverse=True)
        elif sort == "Oldest First":
            items.sort(key=lambda x: x.get("mtime", 0))
        elif sort == "Name (A-Z)":
            items.sort(key=lambda x: x.get("name", "").lower())
        elif sort == "Size (Largest)":
            items.sort(key=lambda x: x.get("size", 0), reverse=True)

        self.current_records = items

        if self.view_mode == "grid":
            self._render_grid(items)
        else:
            self._render_list(items)

    def _render_grid(self, items):
        # Clear grid layout
        while self.grid_layout.count():
            item = self.grid_layout.takeAt(0)
            w = item.widget()
            if w:
                w.deleteLater()

        if not items:
            empty = QLabel("No media found matching this filter.\n\n"
                           "• Take snapshots with the PC webcam\n"
                           "• Or share your phone's camera roll in the Lanyard Android app")
            empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
            empty.setStyleSheet("color: #718096; font-size: 14px; padding: 40px;")
            self.grid_layout.addWidget(empty, 0, 0, 1, 4)
            return

        columns = max(2, min(5, (self.width() - 40) // 230 or 3))
        for idx, rec in enumerate(items):
            row = idx // columns
            col = idx % columns
            card = MediaCard(rec, self.app)
            card.double_clicked.connect(lambda r, i=idx: self.open_lightbox(i))
            self.grid_layout.addWidget(card, row, col)

    def _render_list(self, items):
        self.table_list.setRowCount(len(items))
        for row, rec in enumerate(items):
            item_name = QTableWidgetItem(rec.get("name", ""))
            item_name.setData(Qt.ItemDataRole.UserRole, rec)

            dev_name = rec.get("device_name") or ("This PC" if not rec.get("remote") else rec.get("folder", "Device"))
            item_dev = QTableWidgetItem(f"📱 {dev_name}" if rec.get("remote") else f"💻 {dev_name}")

            item_type = QTableWidgetItem(rec.get("type_name", "Media"))
            item_date = QTableWidgetItem(rec.get("date_str", ""))
            item_size = QTableWidgetItem(rec.get("size_str", ""))

            self.table_list.setItem(row, 0, item_name)
            self.table_list.setItem(row, 1, item_dev)
            self.table_list.setItem(row, 2, item_type)
            self.table_list.setItem(row, 3, item_date)
            self.table_list.setItem(row, 4, item_size)

    def _on_table_double_clicked(self, item):
        row = item.row()
        self.open_lightbox(row)

    def open_lightbox(self, index):
        if not self.current_records or not (0 <= index < len(self.current_records)):
            return
        modal = MediaViewerModal(self.current_records, index, self.app, self)
        modal.exec()

