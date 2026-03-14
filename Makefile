# Makefile for any-file-android
# Requires: JDK 17+, Android SDK, adb (for device targets)

.PHONY: build build-release test test-android test-e2e install install-build lint clean setup help

# APK output path
APK_DEBUG=app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE=app/build/outputs/apk/release/app-release-unsigned.apk

# Default target
all: help

## Build targets

build: ## Build debug APK
	./gradlew assembleDebug

build-release: ## Build release APK (unsigned)
	./gradlew assembleRelease

## Test targets

test: ## Run unit tests (fast, no device needed)
	./gradlew test

test-android: ## Run instrumentation tests (device/emulator required)
	./gradlew connectedAndroidTest

test-e2e: ## Run E2E emulator tests (requires Go daemon + emulator running)
	./test-emulator-e2e.sh

test-coverage: ## Run unit tests with coverage report
	./gradlew testDebugUnitTestCoverage
	@echo "Coverage report: app/build/reports/coverage/test/debug/index.html"

## Device targets

install: ## Install debug APK on connected device/emulator
	adb install -r $(APK_DEBUG)

install-build: build install ## Build and install debug APK

## Code quality targets

lint: ## Run Android lint
	./gradlew lint
	@echo "Lint report: app/build/reports/lint-results-debug.html"

## Clean targets

clean: ## Clean all build outputs
	./gradlew clean

## Setup targets

setup: ## Install pre-commit hooks
	pre-commit install
	pre-commit install --hook-type pre-push
	@echo "✓ Pre-commit hooks installed"

## Help

help: ## Show this help message
	@echo "any-file-android Makefile"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-20s %s\n", $$1, $$2}'
