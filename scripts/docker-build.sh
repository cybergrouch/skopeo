#!/bin/bash
# SPDX-FileCopyrightText: 2026 Lange Pantoja
# SPDX-License-Identifier: AGPL-3.0-or-later

# Docker build helper script for Tennis Levelr
# Usage: ./scripts/docker-build.sh [version]
# Example: ./scripts/docker-build.sh 1.0.0

set -e

VERSION=${1:-latest}
IMAGE_NAME="tennis-levelr"

echo "======================================"
echo "  Building Docker Image"
echo "======================================"
echo ""
echo "Image: ${IMAGE_NAME}:${VERSION}"
echo "Dockerfile: ./Dockerfile"
echo ""

# Check if Dockerfile exists
if [ ! -f "Dockerfile" ]; then
    echo "Error: Dockerfile not found in current directory"
    echo "Please run this script from the project root"
    exit 1
fi

# Build the image
echo "Building image..."
docker build -t ${IMAGE_NAME}:${VERSION} .

# Tag as latest if version is not latest
if [ "$VERSION" != "latest" ]; then
    echo ""
    echo "Tagging as latest..."
    docker tag ${IMAGE_NAME}:${VERSION} ${IMAGE_NAME}:latest
fi

echo ""
echo "======================================"
echo "  Build Complete!"
echo "======================================"
echo ""
echo "Image: ${IMAGE_NAME}:${VERSION}"
echo "Size: $(docker images ${IMAGE_NAME}:${VERSION} --format "{{.Size}}")"
echo ""
echo "To run the container:"
echo "  docker run -p 8080:8080 ${IMAGE_NAME}:${VERSION}"
echo ""
echo "Or use docker-compose:"
echo "  docker-compose up"
echo ""
