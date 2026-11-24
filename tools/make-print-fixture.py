#!/usr/bin/env python3
"""Deterministic NON-PRIVATE rendered-print fixture: 900x500, black DejaVu Sans 40 pt on white, four lines.

This is a REGENERATED fixture (the historical 900x500 image mentioned in HANDOFF was not retained); its identity is the
SHA-256 of the committed PNG at src/test/resources/fixtures/print-900x500.png. Usage: make-print-fixture.py OUT.png
"""
import sys
from PIL import Image, ImageDraw, ImageFont
LINES = ["Hello world 123", "Pack my box 456", "Quick brown fox -78.5", "Price: $12.50 total"]
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 40)
im = Image.new('RGB', (900, 500), 'white')
d = ImageDraw.Draw(im)
for i, t in enumerate(LINES):
    d.text((50, 40 + i * 110), t, font=font, fill='black')
im.save(sys.argv[1], optimize=False)
