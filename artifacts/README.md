# Reproducibility artifacts

Large runtime assets are published in the [`data-v14` GitHub Release](https://github.com/4dai-xyz/AR_Navigation_YPF/releases/tag/data-v14). The source repository keeps only this manifest so ordinary clones remain small and do not require Git LFS.

## Release assets

Assets larger than the reliable upload window are split into 90 MB parts. [`data-v14-assets.sha256`](data-v14-assets.sha256) contains the digest of every published asset.

| Restored file | Published assets | Size | Restored SHA-256 |
| --- | --- | ---: | --- |
| `segmentation_training_data.zip` | Single asset | 71.16 MB | `29b8910b46affbc77f81268caf21e098c42fbf575348bb26042363b92fc529a0` |
| `ocr_training_data.zip` | `ocr_training_data.zip.part01` through `part05` | 426.86 MB | `0d6b44aefa4c09a135c96601f416c3824697f5b7fd6aa64739551daea6fd6742` |
| `session_20260710_154536_478.zip` | `session_20260710_154536_478.zip.part01` through `part05` | 410.35 MB | `70e4bcabe380d4294912a11a852b5e7ae6505578019e5ec6cb7c90f6b4fac988` |
| `runtime_models.zip` | `runtime_models.zip.part01` through `part07` | 600.94 MB | `f7182e526a57454d7e150b80a6eeb2b845797cb8318ae5087d5e5215c55486a6` |
| `ocr_final_models.zip` | Single asset | 158.92 MB | `7080ec6e88cd45d7b891249e0a82caca4b1ae6d077334f585e881b9857ecfa3b` |
| `source_videos.zip` | `source_videos.zip.part01` through `part05` | 404.28 MB | `fe57cd2cbf3d34737ae8b6b2473f33bac4d158091a3679f1dd27b34e0a916cb9` |
| `video_bev_pose_rot_v14.mp4` | `video_bev_pose_rot_v14.mp4.part01` through `part05` | 363.43 MB | `598ffe36dd2e55df3e6a3c57b4649095e79f8ee05cfceeeee4a27450bc3ea6fe` |

Download all assets into one directory, then rebuild split files:

```powershell
function Join-Parts([string]$Pattern, [string]$OutputPath) {
    $output = [System.IO.File]::Create($OutputPath)
    try {
        Get-ChildItem -File -Filter $Pattern | Sort-Object Name | ForEach-Object {
            $input = [System.IO.File]::OpenRead($_.FullName)
            try { $input.CopyTo($output) } finally { $input.Dispose() }
        }
    } finally {
        $output.Dispose()
    }
}

Join-Parts 'ocr_training_data.zip.part*' 'ocr_training_data.zip'
Join-Parts 'session_20260710_154536_478.zip.part*' 'session_20260710_154536_478.zip'
Join-Parts 'runtime_models.zip.part*' 'runtime_models.zip'
Join-Parts 'source_videos.zip.part*' 'source_videos.zip'
Join-Parts 'video_bev_pose_rot_v14.mp4.part*' 'video_bev_pose_rot_v14.mp4'
```

Verify restored files against the table, then extract the archives from the repository root:

```powershell
tar -xf segmentation_training_data.zip -C .
tar -xf ocr_training_data.zip -C .
tar -xf session_20260710_154536_478.zip -C .
tar -xf runtime_models.zip -C .
tar -xf ocr_final_models.zip -C .
tar -xf source_videos.zip -C .
```

Generated intermediate videos, duplicate source archives, Kalibr bags, and obsolete session outputs remain excluded.
