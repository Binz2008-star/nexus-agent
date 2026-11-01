# syntax=docker/dockerfile:1
FROM python:3.12-slim

# Fail fast on malformed env; emit unbuffered logs (Cloud Run/Cloud Logging).
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Layer the dependency install before source so code changes don't bust the pip cache.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Only the service modules ship; tests/, db/, cloudrun/ are excluded via .dockerignore.
COPY *.py ./

# Run as an unprivileged, non-root user (Cloud Run best practice; container contract expects 8080).
RUN useradd --create-home --uid 1000 appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# No shell form (no signal-handling surprises); uvicorn is the single process (Cloud Run scales
# instances, not workers-per-instance).
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
