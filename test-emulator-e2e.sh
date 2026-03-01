#!/bin/bash
# test-emulator-e2e.sh - Start Android emulator and run E2E tests
#
# This script:
# 1. Starts the Android emulator (if not already running)
# 2. Waits for emulator to be ready
# 3. Sets up ADB port forwarding for any-sync infrastructure
# 4. Runs Android E2E tests
# 5. Reports results
#
# Usage: ./test-emulator-e2e.sh [emulator-name]
#   emulator-name: Name of AVD (default: anyfile_emu)

set -e

# Configuration
AVD_NAME="${1:-anyfile_emu}"
ANDROID_SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMULATOR_CMD="$ANDROID_SDK_ROOT/emulator/emulator"
ADB_CMD="$ANDROID_SDK_ROOT/platform-tools/adb"
ANY_SYNC_DOCKER="${ANY_SYNC_DOCKER:-./docker}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Emoji status
STATUS_OK="✅"
STATUS_FAIL="❌"
STATUS_WARN="⚠️"
STATUS_INFO="ℹ️"
STATUS_RUNNING="🚀"

echo -e "${BLUE}═══════════════════════════════════════════════════${NC}"
echo -e "${BLUE}   Android Emulator E2E Test Runner${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════${NC}"
echo ""
echo -e "${STATUS_INFO} AVD Name: ${AVD_NAME}"
echo -e "${STATUS_INFO} Android SDK: ${ANDROID_SDK_ROOT}"
echo ""

# Function to check if emulator is already running
is_emulator_running() {
    $ADB_CMD devices | grep -q "emulator"
    return $?
}

# Function to check if emulator is ready (boot completed)
is_emulator_ready() {
    # Check if boot animation is finished
    local boot_status=$($ADB_CMD shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$boot_status" = "1" ]
}

# Function to wait for emulator to be ready
wait_for_emulator() {
    local max_attempts=120
    local attempt=0

    echo -e "${YELLOW}Waiting for emulator to boot...${NC}"
    while [ $attempt -lt $max_attempts ]; do
        if is_emulator_ready; then
            echo -e "${GREEN}${STATUS_OK} Emulator is ready!${NC}"
            return 0
        fi
        attempt=$((attempt + 1))
        echo -n "."
        sleep 2
    done

    echo ""
    echo -e "${RED}${STATUS_FAIL} Emulator did not boot within ${max_attempts * 2} seconds${NC}"
    return 1
}

# Function to start emulator
start_emulator() {
    echo -e "${STATUS_RUNNING} Starting emulator: ${AVD_NAME}${NC}"

    # Start emulator in background
    # -no-snapshot-load: Don't load from snapshot (always cold boot for consistency)
    # -no-window: Run without GUI (faster, for CI)
    # -gpu swiftshader_indirect: Software rendering (more compatible)
    # -noaudio: Disable audio (faster)
    # -no-boot-anim: Disable boot animation (faster)
    $EMULATOR_CMD -avd "$AVD_NAME" \
        -no-snapshot-load \
        -no-window \
        -gpu swiftshader_indirect \
        -noaudio \
        -no-boot-anim \
        -camera-back none &

    # Save emulator PID for cleanup
    EMULATOR_PID=$!
    echo $EMULATOR_PID > /tmp/emulator.pid

    echo -e "${GREEN}${STATUS_OK} Emulator started (PID: $EMULATOR_PID)${NC}"
}

# Function to setup ADB port forwarding
setup_port_forwarding() {
    echo ""
    echo -e "${STATUS_INFO} Network configuration...${NC}"
    echo -e "  Using emulator special IP 10.0.2.2 to access host machine"
    echo -e "  ${GREEN}${STATUS_OK} No port forwarding required${NC}"
}

# Function to verify any-sync infrastructure is running
verify_infrastructure() {
    echo ""
    echo -e "${STATUS_INFO} Checking any-sync infrastructure...${NC}"

    # Check coordinator
    if curl -s http://127.0.0.1:1004 > /dev/null 2>&1; then
        echo -e "  ${GREEN}${STATUS_OK} Coordinator (127.0.0.1:1004)${NC}"
    else
        echo -e "  ${YELLOW}${STATUS_WARN} Coordinator not reachable${NC}"
        echo -e "  ${YELLOW}Start with: cd ${ANY_SYNC_DOCKER} && docker compose up -d${NC}"
    fi

    # Check filenode
    if curl -s http://127.0.0.1:1005 > /dev/null 2>&1; then
        echo -e "  ${GREEN}${STATUS_OK} Filenode (127.0.0.1:1005)${NC}"
    else
        echo -e "  ${YELLOW}${STATUS_WARN} Filenode not reachable${NC}"
        echo -e "  ${YELLOW}Start with: cd ${ANY_SYNC_DOCKER} && docker compose up -d${NC}"
    fi
}

# Function to run Android E2E tests
run_e2e_tests() {
    echo ""
    echo -e "${STATUS_RUNNING} Running Android E2E tests...${NC}"
    echo ""

    # Run connected Android tests
    if ./gradlew connectedAndroidTest --stacktrace; then
        echo ""
        echo -e "${GREEN}${STATUS_OK} All Android E2E tests passed!${NC}"
        return 0
    else
        local exit_code=$?
        echo ""
        echo -e "${RED}${STATUS_FAIL} Android E2E tests failed (exit code: $exit_code)${NC}"

        # Show test report location
        if [ -f "app/build/reports/androidTests/connected/index.html" ]; then
            echo -e "${STATUS_INFO} Test report: file://$(pwd)/app/build/reports/androidTests/connected/index.html"
        fi

        return $exit_code
    fi
}

# Function to cleanup
cleanup() {
    if [ "$CLEANUP_EMULATOR" = "true" ] && [ -f /tmp/emulator.pid ]; then
        echo ""
        echo -e "${STATUS_INFO} Stopping emulator...${NC}"
        kill $(cat /tmp/emulator.pid) 2>/dev/null || true
        rm -f /tmp/emulator.pid
        echo -e "${GREEN}${STATUS_OK} Emulator stopped${NC}"
    fi
    # No port forwarding cleanup needed since we use 10.0.2.2
}

# Set trap for cleanup on exit
trap cleanup EXIT

# Main execution
main() {
    # Phase 1: Check if emulator is already running
    echo -e "${BLUE}[Phase 1] Emulator Check${NC}"
    if is_emulator_running; then
        echo -e "${GREEN}${STATUS_OK} Emulator already running${NC}"
        if ! is_emulator_ready; then
            echo -e "${YELLOW}Emulator is running but not ready yet...${NC}"
            wait_for_emulator || exit 1
        else
            echo -e "${GREEN}${STATUS_OK} Emulator is ready!${NC}"
        fi
    else
        start_emulator
        wait_for_emulator || exit 1
    fi

    # Phase 2: Setup port forwarding
    echo ""
    echo -e "${BLUE}[Phase 2] Port Forwarding${NC}"
    setup_port_forwarding

    # Phase 3: Verify infrastructure
    echo ""
    echo -e "${BLUE}[Phase 3] Infrastructure Check${NC}"
    verify_infrastructure

    # Phase 4: Run E2E tests
    echo ""
    echo -e "${BLUE}[Phase 4] E2E Test Execution${NC}"
    run_e2e_tests

    # Exit with test result
    exit $?
}

# Run main function
main "$@"
