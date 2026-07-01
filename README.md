# PVC Card Print Studio

A Python desktop application for printing PVC cards. It fetches a card ratio from a server, automatically center-crops uploaded artwork to that ratio, previews the card, and can save or send a print-ready PNG to the operating system print handler.

## Features

- Native PC desktop UI built with Python and Tkinter.
- Server-driven card ratio support.
- Accepts ratio payloads in multiple formats:
  - `{ "width": 86, "height": 54 }`
  - `{ "ratio": 1.5926 }`
  - `{ "aspectRatio": "86:54" }`
  - `{ "aspect_ratio": "86:54" }`
  - `{ "size": "86:54" }`
- Manual card size override in millimeters.
- Center-cover crop preview for uploaded images.
- Saves a 300 DPI print-ready PNG.
- Sends the generated card image to the operating system print handler.

## Requirements

- Python 3.11+
- Pillow

## Run locally

```bash
python -m pip install -r requirements.txt
python -m src.pvc_card_print_studio
```

## Test

```bash
python -m unittest discover -s tests
```

## Next production steps

- Add printer profile storage for different PVC card printers.
- Add calibration offsets and bleed/safe-area overlays.
- Add a Windows installer with PyInstaller or Briefcase.
- Secure ratio endpoints with authentication if ratios are customer- or tenant-specific.
