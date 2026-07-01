from __future__ import annotations

import json
import os
import platform
import subprocess
import tempfile
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from urllib.request import Request, urlopen

from PIL import Image, ImageTk

from .core import CardRatio, create_print_image, parse_ratio_payload

DEFAULT_CARD = CardRatio(86, 54)


class PVCPrintStudio(tk.Tk):
    """Tkinter desktop UI for cropping and printing PVC card artwork."""

    def __init__(self) -> None:
        super().__init__()
        self.title("PVC Card Print Studio")
        self.geometry("1120x760")
        self.minsize(980, 680)

        self.ratio = DEFAULT_CARD
        self.source_image: Image.Image | None = None
        self.preview_photo: ImageTk.PhotoImage | None = None
        self.print_image: Image.Image | None = None
        self.last_output_path: Path | None = None

        self._build_ui()
        self._render_preview()

    def _build_ui(self) -> None:
        self.columnconfigure(1, weight=1)
        self.rowconfigure(0, weight=1)

        controls = ttk.Frame(self, padding=24)
        controls.grid(row=0, column=0, sticky="ns")
        controls.columnconfigure(0, weight=1)

        ttk.Label(controls, text="PVC Card Print Studio", font=("Segoe UI", 18, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Label(
            controls,
            text="Fetch a server ratio, load artwork, auto-crop, then print or save a PVC card image.",
            wraplength=310,
        ).grid(row=1, column=0, sticky="w", pady=(8, 20))

        ttk.Label(controls, text="Ratio endpoint").grid(row=2, column=0, sticky="w")
        self.endpoint_var = tk.StringVar()
        ttk.Entry(controls, textvariable=self.endpoint_var, width=44).grid(row=3, column=0, sticky="ew", pady=(4, 8))
        ttk.Button(controls, text="Fetch ratio", command=self._fetch_ratio).grid(row=4, column=0, sticky="ew")
        ttk.Button(controls, text="Use CR80 default", command=self._use_default_ratio).grid(row=5, column=0, sticky="ew", pady=(8, 18))

        size_frame = ttk.LabelFrame(controls, text="Manual card size (mm)", padding=12)
        size_frame.grid(row=6, column=0, sticky="ew")
        size_frame.columnconfigure((0, 1), weight=1)
        self.width_var = tk.StringVar(value=str(DEFAULT_CARD.width))
        self.height_var = tk.StringVar(value=str(DEFAULT_CARD.height))
        ttk.Label(size_frame, text="Width").grid(row=0, column=0, sticky="w")
        ttk.Label(size_frame, text="Height").grid(row=0, column=1, sticky="w", padx=(10, 0))
        ttk.Entry(size_frame, textvariable=self.width_var).grid(row=1, column=0, sticky="ew")
        ttk.Entry(size_frame, textvariable=self.height_var).grid(row=1, column=1, sticky="ew", padx=(10, 0))
        ttk.Button(size_frame, text="Apply size", command=self._apply_manual_size).grid(row=2, column=0, columnspan=2, sticky="ew", pady=(10, 0))

        ttk.Button(controls, text="Upload image", command=self._open_image).grid(row=7, column=0, sticky="ew", pady=(22, 8))
        self.print_button = ttk.Button(controls, text="Print card", command=self._print_card, state=tk.DISABLED)
        self.print_button.grid(row=8, column=0, sticky="ew")
        self.save_button = ttk.Button(controls, text="Save print-ready PNG", command=self._save_card, state=tk.DISABLED)
        self.save_button.grid(row=9, column=0, sticky="ew", pady=(8, 18))

        self.status_var = tk.StringVar(value="Ready. Load an image to begin.")
        ttk.Label(controls, textvariable=self.status_var, wraplength=310).grid(row=10, column=0, sticky="ew")

        preview = ttk.Frame(self, padding=24)
        preview.grid(row=0, column=1, sticky="nsew")
        preview.columnconfigure(0, weight=1)
        preview.rowconfigure(1, weight=1)
        self.ratio_label = ttk.Label(preview, text="86:54 ratio", font=("Segoe UI", 16, "bold"))
        self.ratio_label.grid(row=0, column=0, sticky="w")
        self.canvas = tk.Canvas(preview, bg="#f8fafc", highlightthickness=1, highlightbackground="#cbd5e1")
        self.canvas.grid(row=1, column=0, sticky="nsew", pady=(16, 0))
        self.canvas.bind("<Configure>", lambda _event: self._render_preview())

    def _fetch_ratio(self) -> None:
        endpoint = self.endpoint_var.get().strip()
        if not endpoint:
            self._set_status("Enter a ratio endpoint URL first.")
            return

        try:
            request = Request(endpoint, headers={"Accept": "application/json"})
            with urlopen(request, timeout=10) as response:
                payload = json.loads(response.read().decode("utf-8"))
            self._set_ratio(parse_ratio_payload(payload))
            self._set_status("Ratio loaded from server.")
        except Exception as exc:  # noqa: BLE001 - show operator-friendly error in desktop UI.
            messagebox.showerror("Ratio fetch failed", str(exc))
            self._set_status(f"Could not fetch ratio: {exc}")

    def _use_default_ratio(self) -> None:
        self._set_ratio(DEFAULT_CARD)
        self._set_status("Using standard CR80 PVC card ratio.")

    def _apply_manual_size(self) -> None:
        try:
            self._set_ratio(CardRatio(float(self.width_var.get()), float(self.height_var.get())))
            self._set_status("Manual card size applied.")
        except ValueError as exc:
            messagebox.showerror("Invalid card size", str(exc))

    def _open_image(self) -> None:
        file_path = filedialog.askopenfilename(
            title="Choose card artwork",
            filetypes=[("Image files", "*.png *.jpg *.jpeg *.bmp *.webp"), ("All files", "*.*")],
        )
        if not file_path:
            return

        try:
            self.source_image = Image.open(file_path)
            self._set_status(f"Loaded {Path(file_path).name}. Preview is auto-cropped to {self.ratio.label}.")
            self._render_preview()
        except Exception as exc:  # noqa: BLE001 - show operator-friendly error in desktop UI.
            messagebox.showerror("Image load failed", str(exc))

    def _set_ratio(self, ratio: CardRatio) -> None:
        self.ratio = ratio
        self.width_var.set(str(ratio.width))
        self.height_var.set(str(ratio.height))
        self.ratio_label.configure(text=f"{ratio.label} ratio")
        self._render_preview()

    def _render_preview(self) -> None:
        self.canvas.delete("all")
        canvas_width = max(self.canvas.winfo_width(), 640)
        canvas_height = max(self.canvas.winfo_height(), 420)
        max_width = canvas_width - 48
        max_height = canvas_height - 48
        preview_width = min(max_width, int(max_height * self.ratio.aspect))
        preview_height = int(preview_width / self.ratio.aspect)
        if preview_height > max_height:
            preview_height = max_height
            preview_width = int(preview_height * self.ratio.aspect)

        x = (canvas_width - preview_width) // 2
        y = (canvas_height - preview_height) // 2

        if not self.source_image:
            self.canvas.create_rectangle(x, y, x + preview_width, y + preview_height, fill="white", outline="#94a3b8")
            self.canvas.create_text(canvas_width // 2, canvas_height // 2, text="Upload artwork to preview auto crop", fill="#64748b")
            self.print_button.configure(state=tk.DISABLED)
            self.save_button.configure(state=tk.DISABLED)
            return

        self.print_image = create_print_image(self.source_image, self.ratio)
        preview_image = self.print_image.copy()
        preview_image.thumbnail((preview_width, preview_height), Image.Resampling.LANCZOS)
        self.preview_photo = ImageTk.PhotoImage(preview_image)
        self.canvas.create_image(canvas_width // 2, canvas_height // 2, image=self.preview_photo)
        self.canvas.create_rectangle(x, y, x + preview_width, y + preview_height, outline="#2563eb", width=2)
        self.print_button.configure(state=tk.NORMAL)
        self.save_button.configure(state=tk.NORMAL)

    def _save_card(self) -> None:
        if not self.print_image:
            return
        file_path = filedialog.asksaveasfilename(
            title="Save print-ready card",
            defaultextension=".png",
            filetypes=[("PNG image", "*.png")],
        )
        if file_path:
            self.print_image.save(file_path, dpi=(300, 300))
            self.last_output_path = Path(file_path)
            self._set_status(f"Saved print-ready card to {file_path}.")

    def _print_card(self) -> None:
        if not self.print_image:
            return

        output_path = Path(tempfile.gettempdir()) / "pvc-card-print.png"
        self.print_image.save(output_path, dpi=(300, 300))
        self.last_output_path = output_path

        try:
            self._send_to_printer(output_path)
            self._set_status("Sent card to the operating system print handler.")
        except Exception as exc:  # noqa: BLE001 - show operator-friendly error in desktop UI.
            messagebox.showerror("Print failed", str(exc))
            self._set_status(f"Could not print automatically. Saved file at {output_path}.")

    def _send_to_printer(self, output_path: Path) -> None:
        system = platform.system()
        if system == "Windows":
            os.startfile(output_path, "print")  # type: ignore[attr-defined]
        elif system == "Darwin":
            subprocess.run(["lp", str(output_path)], check=True)
        else:
            subprocess.run(["xdg-open", str(output_path)], check=True)

    def _set_status(self, message: str) -> None:
        self.status_var.set(message)


def main() -> None:
    app = PVCPrintStudio()
    app.mainloop()


if __name__ == "__main__":
    main()
