# OCR / Logo Quickstart

This project contains the OCR/logo annotation, training, and inference scripts. Use branch `main`.

## Clean clone

```bash
git clone --branch main https://github.com/4dai-xyz/AR_Navigation_YPF.git
cd AR_Navigation_YPF/ocr-and-dataset-main
```

The clone intentionally excludes the large/private datasets and model checkpoints used by the historical README (`char_dataset/`, `cnn_dataset/`, `Huichang_RCNN_Dataset/`, `checkpoints*/`, and `*.pth` are not a guaranteed clean-clone input). Put those assets outside Git and pass/update paths in the script you run.

## First check

```bash
python -m compileall -q .
```

Expected result: exit code `0`. This project has no checked-in automated test suite and its GUI/video scripts cannot produce a meaningful result without data and weights.

## First real run

After installing `torch`, `torchvision`, `opencv-contrib-python`, `pillow`, and `numpy`, prepare a local video and matching checkpoint paths, then run `final_chuli.py` (or `final_test.py` for the venue demo). Update `VIDEO_PATH`, checkpoint, and output constants at the top of the chosen script first.

Expected result: an annotated MP4 such as `result_video.mp4` or `huichang_test_result.mp4`. Missing-file errors mean the external data/weights have not been mounted; they do not indicate missing source code in the clone.
