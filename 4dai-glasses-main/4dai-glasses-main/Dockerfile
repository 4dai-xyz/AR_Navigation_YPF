FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    AI_GLASSES_SERVICE_MODE=pc_backend \
    AI_GLASSES_RECOGNITION_MODE=template

WORKDIR /app

COPY cloud /tmp/cloud
RUN python -m pip install --upgrade pip \
    && python -m pip install /tmp/cloud

COPY cloud /app/cloud
COPY mapping/examples/venue-package-example /app/mapping/examples/venue-package-example

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/v1/health', timeout=3)"

CMD ["uvicorn", "cloud.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
