#!/usr/bin/env bash
set -euo pipefail

REPO_NAME="UIScope-jvm"
BRANCH="main"

if [ -z "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]; then
  echo "ERROR: GITHUB_PERSONAL_ACCESS_TOKEN is not set." >&2
  exit 1
fi

echo ">>> Fetching GitHub username..."
GITHUB_USERNAME=$(curl -s -H "Authorization: token ${GITHUB_PERSONAL_ACCESS_TOKEN}" \
  https://api.github.com/user | grep '"login"' | head -1 | sed 's/.*"login": "\([^"]*\)".*/\1/')

if [ -z "$GITHUB_USERNAME" ]; then
  echo "ERROR: Could not determine GitHub username. Check your GITHUB_PERSONAL_ACCESS_TOKEN." >&2
  exit 1
fi

echo ">>> GitHub user: $GITHUB_USERNAME"
echo ">>> Ensuring repo '$REPO_NAME' exists..."

REPO_CHECK=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: token ${GITHUB_PERSONAL_ACCESS_TOKEN}" \
  "https://api.github.com/repos/${GITHUB_USERNAME}/${REPO_NAME}")

if [ "$REPO_CHECK" = "404" ]; then
  echo ">>> Creating repo '$REPO_NAME'..."
  curl -s -X POST \
    -H "Authorization: token ${GITHUB_PERSONAL_ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    https://api.github.com/user/repos \
    -d "{
      \"name\": \"${REPO_NAME}\",
      \"description\": \"UIScope — See what your UI is made of. Kotlin Compose Multiplatform desktop UI inspector.\",
      \"private\": false,
      \"auto_init\": false
    }" > /dev/null
  echo ">>> Repo created."
else
  echo ">>> Repo already exists."
fi

REMOTE_URL="https://${GITHUB_PERSONAL_ACCESS_TOKEN}@github.com/${GITHUB_USERNAME}/${REPO_NAME}.git"

cd /home/runner/workspace

# Wait for any existing git lock to clear (up to 15 s)
for i in $(seq 1 15); do
  if [ ! -f ".git/config.lock" ] && [ ! -f ".git/index.lock" ]; then
    break
  fi
  echo ">>> Waiting for git lock to clear ($i/15)..."
  sleep 1
done
# Remove stale locks (safe — no concurrent git write is expected after waiting)
rm -f .git/config.lock .git/index.lock 2>/dev/null || true

git config user.email "uiscope-push@replit.com" 2>/dev/null || true
git config user.name "UIScope Push" 2>/dev/null || true

echo ">>> Staging all files..."
git add -A

TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
git diff --cached --quiet && echo ">>> Nothing to commit — working tree clean." || \
  git commit -m "chore: sync to UIScope-jvm [$TIMESTAMP]"

echo ">>> Pushing to $GITHUB_USERNAME/$REPO_NAME ($BRANCH)..."
git push "$REMOTE_URL" "HEAD:${BRANCH}" --force

# ── Tag v1.0.0 to trigger GitHub Actions release build ───────────────────────
TAG="v1.0.0"
echo ""
echo ">>> Tagging $TAG to trigger CI release build..."
# Delete remote tag first (idempotent)
git push "$REMOTE_URL" ":refs/tags/${TAG}" 2>/dev/null || true
# Re-create annotated tag locally (force in case it exists) and push
git tag -f -a "${TAG}" -m "UIScope ${TAG} — Initial release

All four product modes in one installer:
- PC Inspector (Windows/macOS/Linux via JNA accessibility APIs)
- Android Inspector (ADB, no on-device agent)
- Diff Mode (compare two saved sessions)
- Watch Mode (monitor element conditions on Android)"
git push "$REMOTE_URL" "${TAG}"
echo ">>> Tag ${TAG} pushed — GitHub Actions build triggered!"
echo ">>> Watch: https://github.com/${GITHUB_USERNAME}/${REPO_NAME}/actions"

echo ""
echo "✓ Done! https://github.com/${GITHUB_USERNAME}/${REPO_NAME}"
