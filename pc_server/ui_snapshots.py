"""Render every companion page to PNG so CI can smoke-test the Qt UI and reviewers can see it.

Usage: python ui_snapshots.py <output_dir>
Set QT_SCALE_FACTOR (e.g. 1.5) to capture a high-DPI variant. No server sockets are opened.
"""

import os
import re
import sys
from pathlib import Path

import app_gui
from PySide6.QtWidgets import QApplication


def main():
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "ui-snapshots")
    out_dir.mkdir(parents=True, exist_ok=True)
    scale_tag = os.environ.get("QT_SCALE_FACTOR", "1").replace(".", "_")

    app_gui.set_windows_dpi_awareness()
    app = QApplication(sys.argv)
    app_gui.CompanionApp.start_server = lambda self: None
    app_gui.CompanionApp._heal_startup_path_if_registered = lambda self: None

    win = app_gui.CompanionApp()
    win.resize(1280, 820)
    win.show()
    app.processEvents()

    for index, button in enumerate(win.nav_buttons):
        win._switch_page(index)
        for _ in range(5):
            app.processEvents()
        slug = re.sub(r"[^a-z0-9]+", "-", button.text().lower()).strip("-")
        target = out_dir / f"x{scale_tag}-{index + 1:02d}-{slug}.png"
        if not win.grab().save(str(target)):
            raise SystemExit(f"Could not save {target}")
        print(f"saved {target}")

    print(f"pages={win.stack.count()} nav_buttons={len(win.nav_buttons)}", flush=True)
    return 0 if win.stack.count() == len(win.nav_buttons) else 3


if __name__ == "__main__":
    import faulthandler
    import traceback

    faulthandler.enable(file=sys.stdout)
    try:
        code = main()
    except BaseException:
        traceback.print_exc(file=sys.stdout)
        code = 2
    print(f"ui_snapshots exit code {code}", flush=True)
    # Skip interpreter/Qt teardown: server daemon threads and the tray icon can stall or crash it.
    os._exit(code)
