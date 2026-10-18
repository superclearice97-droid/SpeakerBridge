"""SpeakerBridge desktop app (PySide6)."""
from __future__ import annotations

import json
import sys
import threading

try:
    from PySide6.QtCore import QObject, Qt, QThread, Signal
    from PySide6.QtGui import QAction, QColor
    from PySide6.QtWidgets import (
        QAbstractItemView, QApplication, QCheckBox, QFileDialog, QHBoxLayout, QHeaderView,
        QLabel, QMainWindow, QMessageBox, QPushButton, QSplitter, QTableWidget,
        QTableWidgetItem, QTextBrowser, QToolBar, QVBoxLayout, QWidget,
    )
except ImportError:  # pragma: no cover
    print("PySide6 is not installed. On CachyOS run:  sudo pacman -S pyside6\n"
          "Or use the terminal scanner:  speakerbridge-scan", file=sys.stderr)
    raise SystemExit(1)

from . import __version__, compat, scanner, store, stream
from .cli import STATUS_SHORT, _proto_labels

STATUS_COLOR = {
    "verified": "#2e9d5b",
    "group": "#3b7dd8",
    "ready_to_test": "#c98a12",
    "control_only": "#8a8f98",
    "not_speaker": "#5c6066",
}
COLUMNS = ["Speaker", "Brand / model", "Address", "Protocols", "Status"]


class ScanWorker(QObject):
    finished = Signal(list)
    failed = Signal(str)

    def __init__(self, timeout: float):
        super().__init__()
        self.timeout = timeout

    def run(self) -> None:
        try:
            self.finished.emit(scanner.scan(self.timeout))
        except Exception as exc:  # surface any network error in the UI
            self.failed.emit(f"{type(exc).__name__}: {exc}")


class StreamWorker(QObject):
    status = Signal(str)
    done = Signal(str)

    def __init__(self, host: str, port: int):
        super().__init__()
        self.host, self.port = host, port
        self.stop = threading.Event()

    def run(self) -> None:
        try:
            stream.run(self.host, self.port, self.stop, on_status=self.status.emit)
            self.done.emit("Stream stopped. Audio is back on this computer.")
        except Exception as exc:
            self.done.emit(f"Couldn't stream: {exc}")


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle(f"SpeakerBridge {__version__}")
        self.resize(1000, 640)
        self.devices: list = []
        self.thread: QThread | None = None
        self.worker: ScanWorker | None = None

        tb = QToolBar("Main")
        tb.setMovable(False)
        self.addToolBar(tb)
        self.scan_action = QAction("Scan network", self)
        self.scan_action.triggered.connect(self.start_scan)
        tb.addAction(self.scan_action)
        export = QAction("Export JSON…", self)
        export.triggered.connect(self.export_json)
        tb.addAction(export)
        tb.addSeparator()
        self.show_all = QCheckBox("Show non-speakers")
        self.show_all.toggled.connect(self.refresh_table)
        tb.addWidget(self.show_all)

        self.table = QTableWidget(0, len(COLUMNS))
        self.table.setHorizontalHeaderLabels(COLUMNS)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().setVisible(False)
        hdr = self.table.horizontalHeader()
        hdr.setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        hdr.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        self.table.itemSelectionChanged.connect(self.show_details)

        self.details = QTextBrowser()
        self.details.setOpenExternalLinks(False)
        self.verify_btn = QPushButton("Mark playback verified")
        self.verify_btn.setToolTip("Use this only after you have heard audio play on this speaker.")
        self.verify_btn.clicked.connect(self.toggle_verified)
        self.verify_btn.setEnabled(False)
        self.stream_btn = QPushButton("Stream this computer's audio here")
        self.stream_btn.setToolTip("Sends everything this computer plays to the selected Cast speaker, "
                                   "group or stereo pair. Stop to switch audio back.")
        self.stream_btn.clicked.connect(self.toggle_stream)
        self.stream_btn.setEnabled(False)
        self.stream_thread: QThread | None = None
        self.stream_worker: StreamWorker | None = None

        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.addWidget(self.details)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(self.stream_btn)
        row.addWidget(self.verify_btn)
        rl.addLayout(row)

        split = QSplitter(Qt.Orientation.Vertical)
        split.addWidget(self.table)
        split.addWidget(right)
        split.setSizes([380, 260])

        self.summary = QLabel("Press “Scan network” to look for speakers on your Wi-Fi.")
        self.summary.setWordWrap(True)
        central = QWidget()
        cl = QVBoxLayout(central)
        cl.addWidget(self.summary)
        cl.addWidget(split)
        self.setCentralWidget(central)
        self.statusBar().showMessage("Discovery shows what a device advertises; "
                                     "‘Verified’ means you confirmed it plays audio.")

    # ------------------------------------------------------------- scanning
    def start_scan(self) -> None:
        if self.thread is not None:
            return
        self.scan_action.setEnabled(False)
        self.summary.setText("Scanning… (about 5 seconds)")
        self.thread = QThread(self)
        self.worker = ScanWorker(4.0)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.finished.connect(self.scan_done)
        self.worker.failed.connect(self.scan_failed)
        self.thread.start()

    def _end_thread(self) -> None:
        if self.thread is not None:
            self.thread.quit()
            self.thread.wait()
            self.thread = None
            self.worker = None
        self.scan_action.setEnabled(True)

    def scan_done(self, devices: list) -> None:
        self._end_thread()
        self.devices = devices
        speakers = [d for d in devices if d.status != "not_speaker"]
        ready = sum(d.status in ("ready_to_test", "group", "verified") for d in devices)
        if not speakers:
            self.summary.setText("No speakers found. Make sure this computer is on the same Wi-Fi "
                                 "as the speakers, then run scripts/diagnose-audio.sh.")
        else:
            self.summary.setText(f"Found {len(speakers)} speaker(s); {ready} can accept "
                                 "SpeakerBridge audio (or already sync as a group).")
        self.refresh_table()

    def scan_failed(self, msg: str) -> None:
        self._end_thread()
        self.summary.setText("Scan failed.")
        QMessageBox.warning(self, "Scan failed", msg)

    # ------------------------------------------------------------- table
    def visible_devices(self) -> list:
        return [d for d in self.devices if self.show_all.isChecked() or d.status != "not_speaker"]

    def refresh_table(self) -> None:
        rows = self.visible_devices()
        self.table.setRowCount(len(rows))
        for r, d in enumerate(rows):
            vals = [d.name or "(unnamed)", f"{d.manufacturer or '?'} {d.model}".strip(),
                    d.ip, _proto_labels(d), STATUS_SHORT.get(d.status, d.status)]
            for c, v in enumerate(vals):
                item = QTableWidgetItem(v)
                if c == 4:
                    item.setForeground(QColor(STATUS_COLOR.get(d.status, "#888")))
                self.table.setItem(r, c, item)
        self.details.clear()
        self.verify_btn.setEnabled(False)
        self.stream_btn.setEnabled(self.stream_worker is not None)

    def selected(self):
        rows = self.table.selectionModel().selectedRows()
        vis = self.visible_devices()
        return vis[rows[0].row()] if rows and rows[0].row() < len(vis) else None

    def show_details(self) -> None:
        d = self.selected()
        if d is None:
            return
        label = compat.load()["status_labels"].get(d.status, d.status)
        html = [f"<h3>{_esc(d.name or '(unnamed)')}</h3>",
                f"<p><b>{_esc(label)}</b></p>",
                f"<p>Brand: {_esc(d.manufacturer or 'unknown')} &nbsp; Model: {_esc(d.model or 'unknown')}"
                f"<br>Address: {_esc(d.ip)} &nbsp; Key: <code>{_esc(d.key)}</code></p>"]
        if d.notes:
            html.append("<ul>" + "".join(f"<li>{_esc(n)}</li>" for n in d.notes) + "</ul>")
        html.append("<p><small>Advertised: " + _esc(", ".join(sorted(d.raw_types)) or "-") + "</small></p>")
        self.details.setHtml("".join(html))
        self.verify_btn.setEnabled(d.status != "not_speaker")
        self.stream_btn.setEnabled(self.stream_worker is not None or "googlecast" in d.protocols)
        self.verify_btn.setText("Remove verified mark" if d.status == "verified"
                                else "Mark playback verified")

    def toggle_verified(self) -> None:
        d = self.selected()
        if d is None:
            return
        if d.status == "verified":
            store.unmark_verified(d.key)
        else:
            store.mark_verified(d.key, d.name, ",".join(d.protocols))
        compat.classify(d, store.verified_keys())
        self.refresh_table()

    # ------------------------------------------------------------- streaming
    def toggle_stream(self) -> None:
        if self.stream_worker is not None:
            self.stream_worker.stop.set()
            self.stream_btn.setEnabled(False)
            return
        d = self.selected()
        if d is None or "googlecast" not in d.protocols:
            return
        self.stream_thread = QThread(self)
        self.stream_worker = StreamWorker(d.ip, d.protocols["googlecast"].port or 8009)
        self.stream_worker.moveToThread(self.stream_thread)
        self.stream_thread.started.connect(self.stream_worker.run)
        self.stream_worker.status.connect(self.statusBar().showMessage)
        self.stream_worker.done.connect(self.stream_done)
        self.stream_btn.setText(f"Stop streaming to {d.name}")
        self.stream_thread.start()

    def stream_done(self, msg: str) -> None:
        if self.stream_thread is not None:
            self.stream_thread.quit()
            self.stream_thread.wait()
        self.stream_thread = self.stream_worker = None
        self.stream_btn.setText("Stream this computer's audio here")
        d = self.selected()
        self.stream_btn.setEnabled(d is not None and "googlecast" in d.protocols)
        self.statusBar().showMessage(msg)

    def closeEvent(self, event) -> None:
        # Always hand audio back to the computer's own speakers before quitting.
        if self.stream_worker is not None:
            self.stream_worker.stop.set()
            if self.stream_thread is not None:
                self.stream_thread.quit()
                self.stream_thread.wait(5000)
        super().closeEvent(event)

    def export_json(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "Export scan", "speakerbridge-scan.json", "JSON (*.json)")
        if path:
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"version": __version__, "devices": [d.to_json() for d in self.devices]},
                          f, indent=2, default=str)


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("SpeakerBridge")
    app.setDesktopFileName("speakerbridge")
    win = MainWindow()
    win.show()
    win.start_scan()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
