# Rufid Artwork

The source image in `artwork/source/rufid-usb-android.png` is the canonical local artwork input for launcher, Fastlane, README, and repository preview assets.

Regenerate derived assets from the project root:

```bash
python scripts/assets/generate_rufid_assets.py
```

The generator uses Lanczos resampling, preserves the source image, and avoids redraw, style transfer, or background removal.
