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

if [ ! -d ".git" ]; then
  echo ">>> Initializing git repository..."
  git init
  git checkout -b "$BRANCH" 2>/dev/null || true
fi

if git remote get-url uiscope-jvm &>/dev/null; then
  git remote set-url uiscope-jvm "$REMOTE_URL"
  echo ">>> Updated remote 'uiscope-jvm'."
else
  git remote add uiscope-jvm "$REMOTE_URL"
  echo ">>> Added remote 'uiscope-jvm'."
fi

git config user.email "uiscope-push@replit.com" 2>/dev/null || true
git config user.name "UIScope Push" 2>/dev/null || true

echo ">>> Staging all files..."
git add -A

TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
git diff --cached --quiet && echo ">>> Nothing to commit — working tree clean." || \
  git commit -m "chore: sync to UIScope-jvm [$TIMESTAMP]"

echo ">>> Pushing to $GITHUB_USERNAME/$REPO_NAME ($BRANCH)..."
git push uiscope-jvm "HEAD:${BRANCH}" --force

echo ""
echo "✓ Done! Code pushed to https://github.com/${GITHUB_USERNAME}/${REPO_NAME}"
