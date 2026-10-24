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
        QLabel, QMainWindow, QMessageBox, QPushButton, QSpinBox, QSplitter, QTableWidget,
        QTableWidgetItem, QTextBrowser, QToolBar, QVBoxLayout, QWidget,
    )
except ImportError:  # pragma: no cover
    print("PySide6 is not installed. On CachyOS run:  sudo pacman -S pyside6\n"
          "Or use the terminal scanner:  speakerbridge-scan", file=sys.stderr)
    raise SystemExit(1)

from . import __version__, compat, mic, scanner, store, stream
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

    def __init__(self, targets: list, source=None):
        super().__init__()
        self.targets, self.source = targets, source
        self.stop = threading.Event()

    def run(self) -> None:
        try:
            failures = stream.run(self.targets, self.stop, source=self.source, on_status=self.status.emit)
            self.done.emit(("Sync clicks stopped." if self.source else
                            "Stream stopped. Audio is back on this computer.")
                           + (f" Not played on: {'; '.join(failures)}" if failures else ""))
        except Exception as exc:
            self.done.emit(f"Couldn't stream: {exc}")


class MicWorker(QObject):
    status = Signal(str)
    done = Signal(dict, list, str)   # delays, problems, error

    def __init__(self, targets: list):
        super().__init__()
        self.targets = targets
        self.cancel = threading.Event()

    def run(self) -> None:
        try:
            delays, problems = mic.calibrate(self.targets, on_status=self.status.emit, cancel=self.cancel)
            self.done.emit(delays, problems, "")
        except Exception as exc:   # MicError, or anything unexpected: report it, never hang
            self.done.emit({}, [], str(exc))


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
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
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
        self.stream_btn.setToolTip("Sends everything this computer plays to the selected speakers "
                                   "(Google Cast or DLNA, any brand). Ctrl+click to pick several. "
                                   "Stop to switch audio back.")
        self.stream_btn.clicked.connect(self.toggle_stream)
        self.stream_btn.setEnabled(False)
        self.stream_thread: QThread | None = None
        self.stream_worker: StreamWorker | None = None
        self.clicks_box = QCheckBox("Play sync clicks")
        self.clicks_box.setToolTip("Stream a click once a second instead of this computer's audio, to line "
                                   "speakers in one room up by ear: raise the sync delay of the speaker that "
                                   "clicks first until you hear a single click.")
        self.delay_box = QSpinBox()
        self.delay_box.setRange(0, stream.MAX_DELAY_MS)
        self.delay_box.setSingleStep(25)
        self.delay_box.setSuffix(" ms")
        self.delay_box.setPrefix("Sync delay ")
        self.delay_box.setToolTip("Holds this speaker back so it lines up with slower ones. Saved, and "
                                  "changes apply straight away while streaming.")
        self.delay_box.setEnabled(False)
        self.delay_box.valueChanged.connect(self.delay_changed)
        self.mic_btn = QPushButton("Line up with microphone…")
        self.mic_btn.setToolTip("Select two or more speakers. Each plays clicks while this computer's "
                                "microphone listens, and their sync delays are set for you. Asks first; "
                                "the recording is never saved or sent anywhere.")
        self.mic_btn.clicked.connect(self.toggle_mic)
        self.mic_btn.setEnabled(False)
        self.mic_thread: QThread | None = None
        self.mic_worker: MicWorker | None = None

        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.addWidget(self.details)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(self.mic_btn)
        row.addWidget(self.delay_box)
        row.addWidget(self.clicks_box)
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
        """The device the details panel shows: the current row, if it is selected."""
        all_sel = self.selected_all()
        cur = self.table.currentRow()
        vis = self.visible_devices()
        if 0 <= cur < len(vis) and vis[cur] in all_sel:
            return vis[cur]
        return all_sel[0] if all_sel else None

    def selected_all(self) -> list:
        vis = self.visible_devices()
        return [vis[r.row()] for r in sorted(self.table.selectionModel().selectedRows(), key=lambda r: r.row())
                if r.row() < len(vis)]

    def streamable_selection(self) -> list:
        return [t for t in (stream.target_for(d) for d in self.selected_all()) if t]

    def _update_lineup_buttons(self) -> None:
        sel = self.streamable_selection()
        self.stream_btn.setEnabled(self.stream_worker is not None or (self.mic_worker is None and bool(sel)))
        self.mic_btn.setEnabled(self.mic_worker is not None or (self.stream_worker is None and len(sel) >= 2))

    def show_details(self) -> None:
        d = self.selected()
        self._update_lineup_buttons()
        self.delay_box.blockSignals(True)
        self.delay_box.setValue(store.delays().get(d.key, 0) if d else 0)
        self.delay_box.blockSignals(False)
        self.delay_box.setEnabled(d is not None and stream.target_for(d) is not None)
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
        self.verify_btn.setText("Remove verified mark" if d.status == "verified"
                                else "Mark playback verified")

    def delay_changed(self, ms: int) -> None:
        d = self.selected()
        if d is None:
            return
        store.save_delay(d.key, ms)
        for t in self.stream_worker.targets if self.stream_worker else []:
            if t.key == d.key:
                t.delay_ms = ms   # the stream picks this up within 20 ms

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
        targets = self.streamable_selection()
        if not targets:
            return
        saved = store.delays()
        for t in targets:
            t.delay_ms = saved.get(t.key, 0)
        self.stream_thread = QThread(self)
        self.stream_worker = StreamWorker(targets, stream.clicks() if self.clicks_box.isChecked() else None)
        self.stream_worker.moveToThread(self.stream_thread)
        self.stream_thread.started.connect(self.stream_worker.run)
        self.stream_worker.status.connect(self.statusBar().showMessage)
        self.stream_worker.done.connect(self.stream_done)
        self.stream_btn.setText(f"Stop streaming to {', '.join(t.name for t in targets)}")
        self.clicks_box.setEnabled(False)
        self.mic_btn.setEnabled(False)
        self.stream_thread.start()

    def stream_done(self, msg: str) -> None:
        if self.stream_thread is not None:
            self.stream_thread.quit()
            self.stream_thread.wait()
        self.stream_thread = self.stream_worker = None
        self.stream_btn.setText("Stream this computer's audio here")
        self.clicks_box.setEnabled(True)
        self._update_lineup_buttons()
        self.statusBar().showMessage(msg)

    # ------------------------------------------------------------- microphone calibration
    def toggle_mic(self) -> None:
        if self.mic_worker is not None:
            self.mic_worker.cancel.set()
            self.mic_btn.setEnabled(False)
            return
        targets = self.streamable_selection()
        if len(targets) < 2 or self.stream_worker is not None:
            return
        yes = QMessageBox.StandardButton.Yes
        if QMessageBox.question(self, "Use the microphone?", mic.CONSENT + "\n\nUse the microphone now?",
                                yes | QMessageBox.StandardButton.No, QMessageBox.StandardButton.No) != yes:
            return
        self.mic_thread = QThread(self)
        self.mic_worker = MicWorker(targets)
        self.mic_worker.moveToThread(self.mic_thread)
        self.mic_thread.started.connect(self.mic_worker.run)
        self.mic_worker.status.connect(self.statusBar().showMessage)
        self.mic_worker.done.connect(self.mic_done)
        self.mic_btn.setText("Stop listening")
        self.stream_btn.setEnabled(False)
        self.mic_thread.start()

    def mic_done(self, delays: dict, problems: list, error: str) -> None:
        names = {t.key: t.name for t in self.mic_worker.targets} if self.mic_worker else {}
        if self.mic_thread is not None:
            self.mic_thread.quit()
            self.mic_thread.wait()
        self.mic_thread = self.mic_worker = None
        self.mic_btn.setText("Line up with microphone…")
        if error == "stopped":
            msg = "Stopped. Delays not changed."
        elif error:
            msg = f"Couldn't line the speakers up: {error}."
        elif len(delays) < 2:
            msg = "Couldn't measure enough speakers to line them up. Delays not changed."
        else:
            for key, ms in delays.items():
                store.save_delay(key, ms)
            msg = "Lined up and saved: " + ", ".join(f"{names.get(k, k)} {ms} ms" for k, ms in delays.items()) + "."
        if problems:
            msg += "\n\nNot measured:\n" + "\n".join(problems)
        self.statusBar().showMessage(msg.split("\n")[0])
        self.show_details()
        if self.isVisible():
            QMessageBox.information(self, "Microphone calibration", msg)

    def closeEvent(self, event) -> None:
        # Always hand audio back to the computer's own speakers, and stop listening, before quitting.
        if self.mic_worker is not None:
            self.mic_worker.cancel.set()
            if self.mic_thread is not None:
                self.mic_thread.quit()
                self.mic_thread.wait(5000)
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
