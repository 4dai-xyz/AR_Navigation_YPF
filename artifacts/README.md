# Reproducibility artifacts

Large runtime assets are published in the [`data-v14` GitHub Release](https://github.com/4dai-xyz/AR_Navigation_YPF/releases/tag/data-v14). The source repository keeps only this manifest so ordinary clones remain small and do not require Git LFS.

## Release assets

| Asset | Size | SHA-256 |
| --- | ---: | --- |
| `segmentation_training_data.zip` | 71.16 MB | `29b8910b46affbc77f81268caf21e098c42fbf575348bb26042363b92fc529a0` |
| `ocr_training_data.zip` | 426.86 MB | `0d6b44aefa4c09a135c96601f416c3824697f5b7fd6aa64739551daea6fd6742` |
| `session_20260710_154536_478.zip` | 410.35 MB | `70e4bcabe380d4294912a11a852b5e7ae6505578019e5ec6cb7c90f6b4fac988` |
| `runtime_models.zip` | 600.94 MB | `f7182e526a57454d7e150b80a6eeb2b845797cb8318ae5087d5e5215c55486a6` |
| `ocr_final_models.zip` | 158.92 MB | `7080ec6e88cd45d7b891249e0a82caca4b1ae6d077334f585e881b9857ecfa3b` |
| `source_videos.zip` | 404.28 MB | `fe57cd2cbf3d34737ae8b6b2473f33bac4d158091a3679f1dd27b34e0a916cb9` |
| `video_bev_pose_rot_v14.mp4` | 363.43 MB | `598ffe36dd2e55df3e6a3c57b4649095e79f8ee05cfceeeee4a27450bc3ea6fe` |

Extract the archives from the repository root to restore their original paths:

```powershell
tar -xf segmentation_training_data.zip -C .
tar -xf ocr_training_data.zip -C .
tar -xf session_20260710_154536_478.zip -C .
tar -xf runtime_models.zip -C .
tar -xf ocr_final_models.zip -C .
tar -xf source_videos.zip -C .
```

Generated intermediate videos, duplicate source archives, Kalibr bags, and obsolete session outputs remain excluded.
