# VisionRoute Quickstart

The product source is `4dai-glasses-main/4dai-glasses-main/` on branch `main`. It contains the Android app/Rokid bridge and the Python PC cloud backend.

## Clean clone

```powershell
git clone --branch main https://github.com/4dai-xyz/AR_Navigation_YPF.git
cd AR_Navigation_YPF/4dai-glasses-main/4dai-glasses-main
```

The Android APK under `android/releases/` is a build artifact and is not guaranteed to exist after cloning. Build it locally. Venue packages and production model checkpoints may also be supplied separately.

## First tests: cloud

Python 3.11+ is required.

```powershell
cd cloud
python -m venv .venv
.\.venv\Scripts\python -m pip install -e .
.\.venv\Scripts\python -m unittest discover -s tests -v
```

Expected result: the cloud unit/smoke suite passes. Start the clone-only demo backend from the project root:

```powershell
cd ..
.\cloud\.venv\Scripts\python -m uvicorn cloud.app.main:app --host 127.0.0.1 --port 8000
```

Expected result: `http://127.0.0.1:8000/api/v1/health` returns a healthy response and `/debug/pairing` opens. The default example venue package is tracked under `mapping/examples/venue-package-example`.

## Docker image

The publishable image contains only the Cloud/FastAPI backend and the tracked example venue package. It does not include Android, ROS2, DPVO, GPU model checkpoints, or private datasets.

From `4dai-glasses-main/4dai-glasses-main/`:

```powershell
docker build -t 4dai/ar_navigation_ypf_4dai.xyz:latest .
docker run --rm -p 8000:8000 4dai/ar_navigation_ypf_4dai.xyz:latest
```

Expected result: the same health and debug endpoints are available on `http://127.0.0.1:8000`. Authenticate to Docker Hub before publishing, then push an immutable commit tag and `latest`:

```powershell
docker login
docker tag 4dai/ar_navigation_ypf_4dai.xyz:latest 4dai/ar_navigation_ypf_4dai.xyz:5dd81f9
docker push 4dai/ar_navigation_ypf_4dai.xyz:5dd81f9
docker push 4dai/ar_navigation_ypf_4dai.xyz:latest
```

## First Android build

With JDK/Android SDK configured:

```powershell
cd android/ai-glasses-poc
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1
```

Expected result: tests pass and the APK is created at `app/build/outputs/apk/debug/app-debug.apk`. Install that APK only after the local cloud health check succeeds; real Rokid hardware and LAN pairing are optional integration steps.
