# Full P2P Android Client Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a full-featured P2P Android file sync client that interoperates with the desktop any-file client using any-sync infrastructure, following the anytype-kotlin architecture pattern.

**Architecture:** Hybrid P2P approach using gomobile-bound Go library with libp2p for direct device-to-device communication on LAN, and any-sync infrastructure (coordinator, filenode, consensus) for authentication, file storage, and internet fallback.

**Tech Stack:**
- **Go 1.24+** with gomobile for core P2P/sync logic
- **Kotlin** with Jetpack Compose for Android UI
- **libp2p** for P2P networking (DHT, mDNS, streams)
- **any-sync SDK** for coordinator, filenode, consensus clients
- **Protobuf** for Go-Kotlin communication
- **Hilt** for dependency injection
- **Android NSD** for local peer discovery

---

## Phase 1: Foundation - Go Library Setup

### Task 1.1: Create Go module structure

**Files:**
- Create: `lib/go.mod`
- Create: `lib/anyfile/mobile.go`

**Step 1: Initialize Go module**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go mod init github.com/anyproto/any-file-mobile
```

**Step 2: Create go.mod with dependencies**

Create `lib/go.mod`:
```go
module github.com/anyproto/any-file-mobile

go 1.24.6

require (
    github.com/anyproto/any-sync v0.28.0
    github.com/libp2p/go-libp2p v0.47.0
    github.com/multiformats/go-multiaddr v0.16.1
    github.com/multiformats/go-multibase v0.2.0
    github.com/zeebo/blake3 v0.2.4
    go.uber.org/zap v1.27.1
    google.golang.org/protobuf v1.36.11
    storj.io/drpc v0.0.34
)

replace github.com/anyproto/any-sync => ../any-sync
```

**Step 3: Download dependencies**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go mod download
```

**Step 4: Commit**

Run:
```bash
cd ~/projects/anyproto/any-file-android
git add lib/go.mod
git commit -m "feat(mobile): initialize Go module with any-sync and libp2p dependencies"
```

### Task 1.2: Create gomobile entry point

**Files:**
- Create: `lib/anyfile/mobile.go`
- Create: `lib/anyfile/panic.go`

**Step 1: Create mobile.go with gomobile exports**

Create `lib/anyfile/mobile.go`:
```go
package anyfile

// #includestdlib
import "C"
import (
    "encoding/json"
    "unsafe"
)

// Mobile is the main entry point for gomobile binding
type Mobile struct {
    app unsafe.Pointer
}

//export NewMobile
func NewMobile() *Mobile {
    return &Mobile{}
}

//export Init
func (m *Mobile) Init(configJSON string) *C.char {
    config, err := parseConfig(configJSON)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    app, err := NewApplication(config)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    m.app = unsafe.Pointer(app)
    return C.CString(toJSON(map[string]string{"status": "ok"}))
}

//export CreateSpace
func (m *Mobile) CreateSpace(name string) *C.char {
    if m.app == nil {
        return C.CString(toJSON(Error{Message: "not initialized"}))
    }

    app := (*Application)(m.app)
    result, err := app.CreateSpace(name)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    return C.CString(toJSON(result))
}

//export GetPeerID
func (m *Mobile) GetPeerID() *C.char {
    if m.app == nil {
        return C.CString(toJSON(Error{Message: "not initialized"}))
    }

    app := (*Application)(m.app)
    return C.CString(toJSON(map[string]string{"peerId": app.PeerID()}))
}

//export Shutdown
func (m *Mobile) Shutdown() *C.char {
    if m.app != nil {
        app := (*Application)(m.app)
        app.Close()
        m.app = nil
    }
    return C.CString(toJSON(map[string]string{"status": "ok"}))
}

//export SetEventHandler
func SetEventHandler(handler unsafe.Pointer) {
    // Will be implemented when we add event system
}

func toJSON(v interface{}) string {
    b, _ := json.Marshal(v)
    return string(b)
}

func main() {} // Required for gomobile
```

**Step 2: Create panic handler**

Create `lib/anyfile/panic.go`:
```go
package anyfile

import (
    "fmt"
    "runtime/debug"
)

// PanicHandler is called on panics
var PanicHandler = func(r interface{}) {
    fmt.Printf("PANIC: %v\n%s\n", r, debug.Stack())
}

func recoverPanic() {
    if r := recover(); r != nil {
        PanicHandler(r)
    }
}
```

**Step 3: Verify compilation**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go build ./anyfile
```

Expected: No errors, binary created

**Step 4: Commit**

Run:
```bash
git add lib/anyfile/
git commit -m "feat(mobile): add gomobile entry point with basic exports"
```

### Task 1.3: Create configuration structures

**Files:**
- Create: `lib/anyfile/config.go`
- Modify: `lib/anyfile/mobile.go`

**Step 1: Create config structures**

Create `lib/anyfile/config.go`:
```go
package anyfile

import (
    "os"
    "path/filepath"
)

// Config holds the mobile application configuration
type Config struct {
    // Local paths
    AccountDir string `json:"accountDir"`

    // any-sync endpoints
    CoordinatorAddr string `json:"coordinatorAddr"`
    FilenodeAddr    string `json:"filenodeAddr"`
    ConsensusAddr   string `json:"consensusAddr"`

    // P2P settings
    Libp2pListenAddrs []string `json:"libp2pListenAddrs"`
    EnableMDNS        bool      `json:"enableMDNS"`
    EnableDHT         bool      `json:"enableDHT"`

    // Logging
    LogLevel string `json:"logLevel"`
}

// DefaultConfig returns a default configuration
func DefaultConfig() *Config {
    homeDir, _ := os.UserHomeDir()
    return &Config{
        AccountDir: filepath.Join(homeDir, ".anyfile-mobile"),

        // Default to local any-sync setup
        CoordinatorAddr: "127.0.0.1:1004",
        FilenodeAddr:    "127.0.0.1:1005",
        ConsensusAddr:   "127.0.0.1:1006",

        // Default libp2p addresses
        Libp2pListenAddrs: []string{
            "/ip4/0.0.0.0/tcp/0",
            "/ip6/::/tcp/0",
        },
        EnableMDNS: true,
        EnableDHT:  true,

        LogLevel: "info",
    }
}

func parseConfig(jsonStr string) (*Config, error) {
    if jsonStr == "" {
        return DefaultConfig(), nil
    }

    var config Config
    if err := json.Unmarshal([]byte(jsonStr), &config); err != nil {
        return nil, err
    }

    // Set defaults for empty fields
    if config.AccountDir == "" {
        homeDir, _ := os.UserHomeDir()
        config.AccountDir = filepath.Join(homeDir, ".anyfile-mobile")
    }
    if config.CoordinatorAddr == "" {
        config.CoordinatorAddr = "127.0.0.1:1004"
    }
    if config.FilenodeAddr == "" {
        config.FilenodeAddr = "127.0.0.1:1005"
    }

    return &config, nil
}
```

**Step 2: Update mobile.go to use config**

Modify `lib/anyfile/mobile.go`:
```go
// Add import at top:
import "encoding/json"

// Replace existing Init function:
func (m *Mobile) Init(configJSON string) *C.char {
    config, err := parseConfig(configJSON)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    // Ensure account directory exists
    if err := os.MkdirAll(config.AccountDir, 0700); err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    // Application will be created in next task
    return C.CString(toJSON(map[string]string{
        "status": "ok",
        "config": "parsed",
    }))
}
```

**Step 3: Test config parsing**

Create `lib/anyfile/config_test.go`:
```go
package anyfile

import (
    "encoding/json"
    "testing"
)

func TestDefaultConfig(t *testing.T) {
    config := DefaultConfig()

    if config.AccountDir == "" {
        t.Error("AccountDir should not be empty")
    }
    if config.CoordinatorAddr == "" {
        t.Error("CoordinatorAddr should not be empty")
    }
    if config.FilenodeAddr == "" {
        t.Error("FilenodeAddr should not be empty")
    }
    if !config.EnableMDNS {
        t.Error("MDNS should be enabled by default")
    }
    if !config.EnableDHT {
        t.Error("DHT should be enabled by default")
    }
}

func TestParseConfig_Empty(t *testing.T) {
    config, err := parseConfig("")
    if err != nil {
        t.Fatalf("parseConfig failed: %v", err)
    }

    // Should have defaults
    if config.AccountDir == "" {
        t.Error("AccountDir should have default")
    }
}

func TestParseConfig_Valid(t *testing.T) {
    input := `{
        "accountDir": "/custom/path",
        "coordinatorAddr": "192.168.1.100:1004",
        "filenodeAddr": "192.168.1.100:1005"
    }`

    config, err := parseConfig(input)
    if err != nil {
        t.Fatalf("parseConfig failed: %v", err)
    }

    if config.AccountDir != "/custom/path" {
        t.Errorf("Expected AccountDir /custom/path, got %s", config.AccountDir)
    }
    if config.CoordinatorAddr != "192.168.1.100:1004" {
        t.Errorf("Expected coordinatorAddr 192.168.1.100:1004, got %s", config.CoordinatorAddr)
    }
}
```

**Step 4: Run tests**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile -v
```

Expected: All tests pass

**Step 5: Commit**

Run:
```bash
git add lib/anyfile/
git commit -m "feat(mobile): add configuration parsing with defaults"
```

### Task 1.4: Create key management

**Files:**
- Create: `lib/anyfile/keys.go`
- Create: `lib/anyfile/keys_test.go`

**Step 1: Write failing test for key generation**

Create `lib/anyfile/keys_test.go`:
```go
package anyfile

import (
    "os"
    "path/filepath"
    "testing"
)

func TestLoadOrGenerateKeys_NewKeys(t *testing.T) {
    // Create temp directory
    tmpDir := t.TempDir()

    peerID, privKey, err := LoadOrGenerateKeys(tmpDir)
    if err != nil {
        t.Fatalf("LoadOrGenerateKeys failed: %v", err)
    }

    if peerID == "" {
        t.Error("peerID should not be empty")
    }
    if privKey == nil {
        t.Error("privKey should not be nil")
    }

    // Verify files were created
    if _, err := os.Stat(filepath.Join(tmpDir, PrivateKeyFile)); os.IsNotExist(err) {
        t.Error("private key file not created")
    }
    if _, err := os.Stat(filepath.Join(tmpDir, PeerIDFile)); os.IsNotExist(err) {
        t.Error("peer ID file not created")
    }
}

func TestLoadOrGenerateKeys_ExistingKeys(t *testing.T) {
    // Create temp directory
    tmpDir := t.TempDir()

    // Generate keys first time
    peerID1, _, err := LoadOrGenerateKeys(tmpDir)
    if err != nil {
        t.Fatalf("first LoadOrGenerateKeys failed: %v", err)
    }

    // Load keys second time
    peerID2, _, err := LoadOrGenerateKeys(tmpDir)
    if err != nil {
        t.Fatalf("second LoadOrGenerateKeys failed: %v", err)
    }

    // Should be same peer ID
    if peerID1 != peerID2 {
        t.Errorf("peerID changed: %s != %s", peerID1, peerID2)
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile -v -run TestLoadOrGenerateKeys
```

Expected: FAIL with "undefined: LoadOrGenerateKeys"

**Step 3: Implement key management**

Create `lib/anyfile/keys.go`:
```go
package anyfile

import (
    "crypto/rand"
    "encoding/hex"
    "errors"
    "fmt"
    "os"
    "path/filepath"

    "github.com/libp2p/go-libp2p/core/crypto"
    "github.com/libp2p/go-libp2p/core/peer"
)

const (
    PrivateKeyFile = "peer_private.key"
    PeerIDFile     = "peer.id"
)

// LoadOrGenerateKeys loads existing keys or generates new ones
func LoadOrGenerateKeys(accountDir string) (string, crypto.PrivKey, error) {
    if accountDir == "" {
        return "", nil, errors.New("account directory cannot be empty")
    }

    privKeyPath := filepath.Join(accountDir, PrivateKeyFile)
    peerIDPath := filepath.Join(accountDir, PeerIDFile)

    // Try to load existing keys
    if _, err := os.Stat(privKeyPath); err == nil {
        return loadKeys(privKeyPath, peerIDPath)
    }

    // Generate new keys
    return generateKeys(accountDir, privKeyPath, peerIDPath)
}

func loadKeys(privKeyPath, peerIDPath string) (string, crypto.PrivKey, error) {
    // Read private key
    privKeyBytes, err := os.ReadFile(privKeyPath)
    if err != nil {
        return "", nil, fmt.Errorf("failed to read private key: %w", err)
    }

    privKey, err := crypto.UnmarshalPrivateKey(privKeyBytes)
    if err != nil {
        return "", nil, fmt.Errorf("failed to unmarshal private key: %w", err)
    }

    // Get peer ID from private key
    peerID, err := peer.IDFromPrivateKey(privKey)
    if err != nil {
        return "", nil, fmt.Errorf("failed to get peer ID: %w", err)
    }

    return peerID.String(), privKey, nil
}

func generateKeys(accountDir, privKeyPath, peerIDPath string) (string, crypto.PrivKey, error) {
    // Generate Ed25519 key pair
    privKey, _, err := crypto.GenerateEd25519Key(rand.Reader)
    if err != nil {
        return "", nil, fmt.Errorf("failed to generate key pair: %w", err)
    }

    // Get peer ID
    peerID, err := peer.IDFromPrivateKey(privKey)
    if err != nil {
        return "", nil, fmt.Errorf("failed to get peer ID: %w", err)
    }

    // Marshal private key
    privKeyBytes, err := crypto.MarshalPrivateKey(privKey)
    if err != nil {
        return "", nil, fmt.Errorf("failed to marshal private key: %w", err)
    }

    // Ensure directory exists
    if err := os.MkdirAll(accountDir, 0700); err != nil {
        return "", nil, fmt.Errorf("failed to create account directory: %w", err)
    }

    // Write private key (restricted permissions)
    if err := os.WriteFile(privKeyPath, privKeyBytes, 0600); err != nil {
        return "", nil, fmt.Errorf("failed to write private key: %w", err)
    }

    // Write peer ID
    if err := os.WriteFile(peerIDPath, []byte(peerID.String()), 0644); err != nil {
        return "", nil, fmt.Errorf("failed to write peer ID: %w", err)
    }

    return peerID.String(), privKey, nil
}

// GenerateSpaceID generates a unique space ID
func GenerateSpaceID() string {
    b := make([]byte, 32)
    if _, err := rand.Read(b); err != nil {
        // Fallback to timestamp + random
        return hex.EncodeToString([]byte(fmt.Sprintf("%d", os.Getpid()))) + hex.EncodeToString(b)
    }
    return hex.EncodeToString(b)
}
```

**Step 4: Run tests to verify they pass**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile -v -run TestLoadOrGenerateKeys
```

Expected: PASS

**Step 5: Commit**

Run:
```bash
git add lib/anyfile/
git commit -m "feat(mobile): add peer key generation and loading"
```

### Task 1.5: Create build script for gomobile

**Files:**
- Create: `lib/build.sh`
- Modify: `app/build.gradle.kts`

**Step 1: Create build script**

Create `lib/build.sh`:
```bash
#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/../app/libs"
MODULE_NAME="github.com/anyproto/any-file-mobile/anyfile"
PACKAGE_NAME="com.anyfile.mobile"

echo -e "${GREEN}Building any-file-mobile for Android...${NC}"

# Check prerequisites
if ! command -v gomobile &> /dev/null; then
    echo -e "${RED}Error: gomobile not found${NC}"
    echo "Install with: go install golang.org/x/mobile/cmd/gomobile@latest"
    exit 1
fi

if ! command -v go &> /dev/null; then
    echo -e "${RED}Error: go not found${NC}"
    exit 1
fi

# Clean previous builds
echo "Cleaning previous builds..."
rm -f "$OUTPUT_DIR/anyfile.aar"
rm -f "$OUTPUT_DIR/anyfile-framework"

# Download dependencies
echo "Downloading Go dependencies..."
cd "$SCRIPT_DIR"
go mod download

# Run tests
echo "Running tests..."
go test ./... -short || {
    echo -e "${RED}Tests failed${NC}"
    exit 1
}

# Build AAR
echo "Building AAR..."
gomobile bind -target=android \
    -javapkg=$PACKAGE_NAME \
    -o="$OUTPUT_DIR/anyfile.aar" \
    -tags=mobile \
    $MODULE_NAME

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Build successful${NC}"
    echo "  Output: $OUTPUT_DIR/anyfile.aar"

    # Show file size
    if command -v du &> /dev/null; then
        SIZE=$(du -h "$OUTPUT_DIR/anyfile.aar" | cut -f1)
        echo "  Size: $SIZE"
    fi
else
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi
```

**Step 2: Make build script executable**

Run:
```bash
chmod +x ~/projects/anyproto/any-file-android/lib/build.sh
```

**Step 3: Update app build.gradle.kts to include AAR**

Modify `app/build.gradle.kts`:
```kotlin
android {
    // ... existing config ...

    dependencies {
        // Go library (gomobile)
        implementation(files("libs/anyfile.aar"))

        // Protobuf for Go-Kotlin communication
        implementation("com.google.protobuf:protobuf-javalite:3.25.5")

        // ... existing dependencies ...
    }
}
```

**Step 4: Test build script**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
./build.sh
```

Expected: AAR file created in `app/libs/`

**Step 5: Commit**

Run:
```bash
git add lib/build.sh app/build.gradle.kts
git commit -m "feat(build): add gomobile build script and AAR integration"
```

---

## Phase 2: Application Core - Space Management

### Task 2.1: Create space service

**Files:**
- Create: `lib/anyfile/space/service.go`
- Create: `lib/anyfile/space/creator.go`
- Create: `lib/anyfile/space/storage.go`

**Step 1: Write failing test for space creation**

Create `lib/anyfile/space/service_test.go`:
```go
package space

import (
    "context"
    "testing"
    "time"
)

func TestService_CreateSpace(t *testing.T) {
    ctx := context.Background()

    // Mock coordinator and consensus will be added later
    // For now, test the basic structure
    svc := &Service{}

    info, err := svc.CreateSpace(ctx, "test-space")
    if err != nil {
        t.Fatalf("CreateSpace failed: %v", err)
    }

    if info.SpaceID == "" {
        t.Error("SpaceID should not be empty")
    }
    if info.SpaceHeader == nil {
        t.Error("SpaceHeader should not be nil")
    }
    if info.MasterKey == nil {
        t.Error("MasterKey should not be nil")
    }
    if info.ReadKey == nil {
        t.Error("ReadKey should not be nil")
    }
}
```

**Step 2: Run test to verify it fails**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile/space -v
```

Expected: FAIL with "undefined: Service"

**Step 3: Implement space service structures**

Create `lib/anyfile/space/service.go`:
```go
package space

import (
    "context"
    "fmt"

    "github.com/anyproto/any-sync/commonspace/spacepayloads"
    "github.com/anyproto/any-sync/consensus/consensusproto"
    "github.com/anyproto/any-sync/coordinator/coordinatorproto"
    "github.com/anyproto/any-sync/util/crypto"
    "go.uber.org/zap"
)

// SpaceInfo holds information about a created space
type SpaceInfo struct {
    SpaceID      string
    SpaceHeader  []byte
    SpaceReceipt *coordinatorproto.SpaceReceiptWithSignature
    MasterKey    crypto.PrivKey
    ReadKey      crypto.SymKey
    MetadataKey  crypto.PrivKey
    ValidUntil   uint64
}

// Service handles space creation and management
type Service struct {
    peerID     string
    privKey    crypto.PrivKey
    coord      CoordinatorClient
    consensus  ConsensusClient
    storage    *Storage
    logger     *zap.Logger
}

// CoordinatorClient defines coordinator operations
type CoordinatorClient interface {
    SpaceSign(ctx context.Context, spaceID string, header []byte) (*coordinatorproto.SpaceReceiptWithSignature, error)
}

// ConsensusClient defines consensus operations
type ConsensusClient interface {
    AddLog(ctx context.Context, spaceID string, record *consensusproto.RawRecordWithId) error
}

// NewService creates a new space service
func NewService(
    peerID string,
    privKey crypto.PrivKey,
    coord CoordinatorClient,
    consensus ConsensusClient,
    storage *Storage,
    logger *zap.Logger,
) *Service {
    return &Service{
        peerID:    peerID,
        privKey:   privKey,
        coord:     coord,
        consensus: consensus,
        storage:   storage,
        logger:    logger,
    }
}

// CreateSpace creates a new space with the given name
func (s *Service) CreateSpace(ctx context.Context, spaceName string) (*SpaceInfo, error) {
    s.logger.Info("Creating space", zap.String("name", spaceName))

    // Generate space keys
    masterKey, _, err := crypto.GenerateRandomEd25519KeyPair()
    if err != nil {
        return nil, fmt.Errorf("failed to generate master key: %w", err)
    }

    readKey := crypto.NewAES()
    metadataKey, _, err := crypto.GenerateRandomEd25519KeyPair()
    if err != nil {
        return nil, fmt.Errorf("failed to generate metadata key: %w", err)
    }

    // Create space header payload
    payload := spacepayloads.SpaceCreatePayload{
        SigningKey:   s.privKey,
        SpaceType:    spacepayloads.SpaceTypePersonalSpace,
        SpacePayload: []byte(spaceName),
        MasterKey:    masterKey,
        ReadKey:      readKey,
        MetadataKey:  metadataKey,
        Metadata:     []byte("{}"),
    }

    storagePayload, err := spacepayloads.StoragePayloadForSpaceCreateV1(payload)
    if err != nil {
        return nil, fmt.Errorf("failed to create space storage payload: %w", err)
    }

    spaceID := storagePayload.SpaceHeaderWithId.Id
    header := storagePayload.SpaceHeaderWithId.RawHeader

    // Sign with coordinator
    receipt, err := s.coord.SpaceSign(ctx, spaceID, header)
    if err != nil {
        return nil, fmt.Errorf("failed to sign space: %w", err)
    }

    // Sync ACL to consensus (required for filenode)
    err = s.consensus.AddLog(ctx, spaceID, storagePayload.AclWithId)
    if err != nil {
        s.logger.Warn("Failed to sync ACL to consensus", zap.Error(err))
        // Don't fail - space was created
    }

    // Get valid until from receipt
    receiptPayload := &coordinatorproto.SpaceReceipt{}
    if err := receiptPayload.Unmarshal(receipt.SpaceReceiptPayload); err != nil {
        s.logger.Warn("Failed to unmarshal receipt", zap.Error(err))
    }

    // Store space credentials
    if s.storage != nil {
        if err := s.storage.Store(&StoredSpace{
            SpaceID:     spaceID,
            SpaceName:   spaceName,
            Header:      header,
            Receipt:     receipt,
            MasterKey:   masterKey,
            ReadKey:     readKey,
            MetadataKey: metadataKey,
            PeerID:      s.peerID,
            ValidUntil:  receiptPayload.ValidUntil,
        }); err != nil {
            s.logger.Warn("Failed to store space", zap.Error(err))
        }
    }

    return &SpaceInfo{
        SpaceID:      spaceID,
        SpaceHeader:  header,
        SpaceReceipt: receipt,
        MasterKey:    masterKey,
        ReadKey:      readKey,
        MetadataKey:  metadataKey,
        ValidUntil:   receiptPayload.ValidUntil,
    }, nil
}
```

**Step 4: Create storage backend**

Create `lib/anyfile/space/storage.go`:
```go
package space

import (
    "encoding/json"
    "os"
    "path/filepath"
    "sync"
    "time"
)

// StoredSpace represents a stored space with credentials
type StoredSpace struct {
    SpaceID     string    `json:"spaceId"`
    SpaceName   string    `json:"spaceName"`
    Header      []byte    `json:"header"`
    Receipt     []byte    `json:"receipt"`
    MasterKey   []byte    `json:"masterKey"`
    ReadKey     []byte    `json:"readKey"`
    MetadataKey []byte    `json:"metadataKey"`
    PeerID      string    `json:"peerId"`
    ValidUntil  uint64    `json:"validUntil"`
    CreatedAt   time.Time `json:"createdAt"`
}

// Storage handles space credential persistence
type Storage struct {
    dir  string
    m    sync.RWMutex
    data map[string]*StoredSpace
}

// NewStorage creates a new storage backend
func NewStorage(dir string) (*Storage, error) {
    if err := os.MkdirAll(dir, 0700); err != nil {
        return nil, err
    }

    s := &Storage{
        dir:  dir,
        data: make(map[string]*StoredSpace),
    }

    // Load existing spaces
    if err := s.load(); err != nil {
        return nil, err
    }

    return s, nil
}

// Store saves space credentials
func (s *Storage) Store(space *StoredSpace) error {
    s.m.Lock()
    defer s.m.Unlock()

    space.CreatedAt = time.Now()
    s.data[space.SpaceID] = space

    return s.save()
}

// Get retrieves space credentials
func (s *Storage) Get(spaceID string) (*StoredSpace, bool) {
    s.m.RLock()
    defer s.m.RUnlock()

    space, ok := s.data[spaceID]
    return space, ok
}

// List returns all stored spaces
func (s *Storage) List() []*StoredSpace {
    s.m.RLock()
    defer s.m.RUnlock()

    spaces := make([]*StoredSpace, 0, len(s.data))
    for _, space := range s.data {
        spaces = append(spaces, space)
    }
    return spaces
}

// Delete removes space credentials
func (s *Storage) Delete(spaceID string) error {
    s.m.Lock()
    defer s.m.Unlock()

    delete(s.data, spaceID)
    return s.save()
}

func (s *Storage) load() error {
    path := filepath.Join(s.dir, "spaces.json")

    data, err := os.ReadFile(path)
    if err != nil {
        if os.IsNotExist(err) {
            return nil // First run
        }
        return err
    }

    return json.Unmarshal(data, &s.data)
}

func (s *Storage) save() error {
    path := filepath.Join(s.dir, "spaces.json")

    data, err := json.MarshalIndent(s.data, "", "  ")
    if err != nil {
        return err
    }

    return os.WriteFile(path, data, 0600)
}
```

**Step 5: Run tests to verify they pass**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile/space -v
```

Expected: Tests pass (we'll add mocks later)

**Step 6: Commit**

Run:
```bash
git add lib/anyfile/space/
git commit -m "feat(space): add space service with creation and storage"
```

### Task 2.2: Integrate coordinator client

**Files:**
- Create: `lib/anyfile/coordinator/client.go`
- Create: `lib/anyfile/coordinator/transport.go`

**Step 1: Create coordinator client**

Create `lib/anyfile/coordinator/client.go`:
```go
package coordinator

import (
    "context"
    "fmt"
    "net"
    "time"

    "github.com/anyproto/any-sync/coordinator/coordinatorproto"
    "storj.io/drpc"
    "storj.io/drpc/drpcconn"
)

// Client handles coordinator communication
type Client struct {
    addr string
    conn net.Conn
    drpc drpc.Conn
    client coordinatorproto.DRPCCoordinatorClient
}

// NewClient creates a new coordinator client
func NewClient(addr string) *Client {
    return &Client{addr: addr}
}

// Connect establishes connection to coordinator
func (c *Client) Connect(ctx context.Context) error {
    var d net.Dialer
    conn, err := d.DialContext(ctx, "tcp", c.addr)
    if err != nil {
        return fmt.Errorf("failed to connect to coordinator: %w", err)
    }

    c.conn = conn
    c.drpc = drpcconn.New(conn)
    c.client = coordinatorproto.NewDRPCCoordinatorClient(c.drpc)

    return nil
}

// Close closes the connection
func (c *Client) Close() error {
    if c.drpc != nil {
        c.drpc.Close()
    }
    if c.conn != nil {
        return c.conn.Close()
    }
    return nil
}

// SpaceSign requests space signature
func (c *Client) SpaceSign(ctx context.Context, spaceID string, header []byte) (*coordinatorproto.SpaceReceiptWithSignature, error) {
    if c.client == nil {
        return nil, fmt.Errorf("not connected")
    }

    req := &coordinatorproto.SpaceSignRequest{
        SpaceId: spaceID,
        Header:  header,
    }

    return c.client.SpaceSign(ctx, req)
}

// NetworkConfig gets network configuration
func (c *Client) NetworkConfig(ctx context.Context) (*coordinatorproto.NetworkConfigurationResponse, error) {
    if c.client == nil {
        return nil, fmt.Errorf("not connected")
    }

    return c.client.NetworkConfiguration(ctx, &coordinatorproto.NetworkConfigurationRequest{})
}
```

**Step 2: Create transport with timeout support**

Create `lib/anyfile/coordinator/transport.go`:
```go
package coordinator

import (
    "context"
    "time"
)

// Dialer creates connections with timeout
type Dialer struct {
    timeout time.Duration
}

// NewDialer creates a new dialer
func NewDialer(timeout time.Duration) *Dialer {
    if timeout == 0 {
        timeout = 30 * time.Second
    }
    return &Dialer{timeout: timeout}
}

// DialContext creates a connection with timeout
func (d *Dialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
    ctx, cancel := context.WithTimeout(ctx, d.timeout)
    defer cancel()

    var dialer net.Dialer
    return dialer.DialContext(ctx, network, addr)
}
```

**Step 3: Write integration test**

Create `lib/anyfile/coordinator/integration_test.go`:
```go
//go:build integration
// +build integration

package coordinator

import (
    "context"
    "testing"
    "time"
)

func TestClient_Connect(t *testing.T) {
    // This test requires running coordinator
    ctx := context.Background()

    client := NewClient("127.0.0.1:1004")

    err := client.Connect(ctx)
    if err != nil {
        t.Skipf("Coordinator not available: %v", err)
    }
    defer client.Close()

    // Test network config
    config, err := client.NetworkConfig(ctx)
    if err != nil {
        t.Fatalf("NetworkConfig failed: %v", err)
    }

    if config.NetworkId == "" {
        t.Error("NetworkId should not be empty")
    }

    t.Logf("Connected to network: %s", config.NetworkId)
}
```

**Step 4: Run integration test (if coordinator running)**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile/coordinator -v -tags=integration
```

Expected: Either SKIP if coordinator not running, or PASS if it is

**Step 5: Commit**

Run:
```bash
git add lib/anyfile/coordinator/
git commit -m "feat(coordinator): add DRPC client for space signing"
```

### Task 2.3: Integrate consensus client

**Files:**
- Create: `lib/anyfile/consensus/client.go`

**Step 1: Create consensus client**

Create `lib/anyfile/consensus/client.go`:
```go
package consensus

import (
    "context"
    "fmt"
    "net"

    "github.com/anyproto/any-sync/consensus/consensusproto"
    "storj.io/drpc/drpcconn"
)

// Client handles consensus communication
type Client struct {
    addr   string
    conn   net.Conn
    drpc   drpc.Conn
    client consensusproto.DRPCConsensusClient
}

// NewClient creates a new consensus client
func NewClient(addr string) *Client {
    return &Client{addr: addr}
}

// Connect establishes connection to consensus
func (c *Client) Connect(ctx context.Context) error {
    var d net.Dialer
    conn, err := d.DialContext(ctx, "tcp", c.addr)
    if err != nil {
        return fmt.Errorf("failed to connect to consensus: %w", err)
    }

    c.conn = conn
    c.drpc = drpcconn.New(conn)
    c.client = consensusproto.NewDRPCConsensusClient(c.drpc)

    return nil
}

// Close closes the connection
func (c *Client) Close() error {
    if c.drpc != nil {
        c.drpc.Close()
    }
    if c.conn != nil {
        return c.conn.Close()
    }
    return nil
}

// AddLog adds a log entry (for ACL sync)
func (c *Client) AddLog(ctx context.Context, spaceID string, record *consensusproto.RawRecordWithId) error {
    if c.client == nil {
        return fmt.Errorf("not connected")
    }

    req := &consensusproto.LogAddRequest{
        SpaceId: spaceID,
        LogId:   record.Id,
        Records: []*consensusproto.RawRecordWithId{record},
    }

    _, err := c.client.LogAdd(ctx, req)
    return err
}
```

**Step 2: Write test**

Create `lib/anyfile/consensus/client_test.go`:
```go
package consensus

import (
    "context"
    "testing"
)

func TestClient_AddLog(t *testing.T) {
    // TODO: Add integration test with running consensus
    t.Skip("requires running consensus service")
}
```

**Step 3: Commit**

Run:
```bash
git add lib/anyfile/consensus/
git commit -m "feat(consensus): add DRPC client for ACL sync"
```

### Task 2.4: Create main application structure

**Files:**
- Create: `lib/anyfile/app.go`
- Modify: `lib/anyfile/mobile.go`

**Step 1: Write application structure**

Create `lib/anyfile/app.go`:
```go
package anyfile

import (
    "context"
    "fmt"
    "os"
    "path/filepath"
    "sync"

    "github.com/anyproto/any-file-mobile/anyfile/coordinator"
    "github.com/anyproto/any-file-mobile/anyfile/consensus"
    "github.com/anyproto/any-file-mobile/anyfile/space"
    "go.uber.org/zap"
    "go.uber.org/zap/zapcore"
)

// Application is the main application
type Application struct {
    mu         sync.RWMutex
    config     *Config
    peerID     string
    privKey    crypto.PrivKey

    // Clients
    coord      *coordinator.Client
    consensus  *consensus.Client

    // Services
    spaceSvc   *space.Service
    storage    *space.Storage

    // Logging
    logger     *zap.Logger

    // Lifecycle
    started    bool
    closed     bool
}

// NewApplication creates a new application
func NewApplication(config *Config) (*Application, error) {
    // Setup logging
    logger := newLogger(config.LogLevel)

    app := &Application{
        config: config,
        logger: logger,
    }

    // Load or generate keys
    peerID, privKey, err := LoadOrGenerateKeys(config.AccountDir)
    if err != nil {
        return nil, fmt.Errorf("failed to load keys: %w", err)
    }

    app.peerID = peerID
    app.privKey = privKey

    // Initialize storage
    storageDir := filepath.Join(config.AccountDir, "spaces")
    storage, err := space.NewStorage(storageDir)
    if err != nil {
        return nil, fmt.Errorf("failed to create storage: %w", err)
    }
    app.storage = storage

    // Initialize clients
    if config.CoordinatorAddr != "" {
        app.coord = coordinator.NewClient(config.CoordinatorAddr)
    }
    if config.ConsensusAddr != "" {
        app.consensus = consensus.NewClient(config.ConsensusAddr)
    }

    // Initialize space service
    app.spaceSvc = space.NewService(
        peerID,
        privKey,
        app.coord,
        app.consensus,
        storage,
        logger,
    )

    logger.Info("Application initialized",
        zap.String("peerID", peerID),
        zap.String("accountDir", config.AccountDir),
    )

    return app, nil
}

// Start starts the application
func (a *Application) Start(ctx context.Context) error {
    a.mu.Lock()
    defer a.mu.Unlock()

    if a.started {
        return fmt.Errorf("already started")
    }
    if a.closed {
        return fmt.Errorf("application closed")
    }

    // Connect to coordinator
    if a.coord != nil {
        if err := a.coord.Connect(ctx); err != nil {
            a.logger.Warn("Failed to connect to coordinator", zap.Error(err))
            // Don't fail - coordinator is optional for some operations
        } else {
            a.logger.Info("Connected to coordinator")
        }
    }

    // Connect to consensus
    if a.consensus != nil {
        if err := a.consensus.Connect(ctx); err != nil {
            a.logger.Warn("Failed to connect to consensus", zap.Error(err))
            // Don't fail - consensus is optional
        } else {
            a.logger.Info("Connected to consensus")
        }
    }

    a.started = true
    a.logger.Info("Application started")

    return nil
}

// Close closes the application
func (a *Application) Close() error {
    a.mu.Lock()
    defer a.mu.Unlock()

    if a.closed {
        return nil
    }

    // Close clients
    if a.coord != nil {
        a.coord.Close()
    }
    if a.consensus != nil {
        a.consensus.Close()
    }

    a.closed = true
    a.logger.Info("Application closed")

    return nil
}

// PeerID returns the peer ID
func (a *Application) PeerID() string {
    a.mu.RLock()
    defer a.mu.RUnlock()
    return a.peerID
}

// CreateSpace creates a new space
func (a *Application) CreateSpace(name string) (*space.SpaceInfo, error) {
    a.mu.RLock()
    defer a.mu.RUnlock()

    if !a.started {
        return nil, fmt.Errorf("application not started")
    }

    return a.spaceSvc.CreateSpace(context.Background(), name)
}

// ListSpaces returns all stored spaces
func (a *Application) ListSpaces() []*space.StoredSpace {
    a.mu.RLock()
    defer a.mu.RUnlock()

    return a.storage.List()
}

func newLogger(level string) *zap.Logger {
    var zapLevel zapcore.Level
    switch level {
    case "debug":
        zapLevel = zapcore.DebugLevel
    case "info":
        zapLevel = zapcore.InfoLevel
    case "warn":
        zapLevel = zapcore.WarnLevel
    case "error":
        zapLevel = zapcore.ErrorLevel
    default:
        zapLevel = zapcore.InfoLevel
    }

    config := zap.Config{
        Level:            zap.NewAtomicLevelAt(zapLevel),
        Development:      false,
        Encoding:         "json",
        EncoderConfig:    zap.NewProductionEncoderConfig(),
        OutputPaths:      []string{"stdout"},
        ErrorOutputPaths: []string{"stderr"},
    }

    logger, _ := config.Build()
    return logger
}
```

**Step 2: Update mobile.go to use application**

Modify `lib/anyfile/mobile.go`:
```go
// Replace Init function:
func (m *Mobile) Init(configJSON string) *C.char {
    config, err := parseConfig(configJSON)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    // Create application
    app, err := NewApplication(config)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    // Start application
    ctx := context.Background()
    if err := app.Start(ctx); err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    m.app = unsafe.Pointer(app)

    return C.CString(toJSON(map[string]interface{}{
        "status":  "ok",
        "peerId":  app.PeerID(),
        "started": true,
    }))
}

// Update CreateSpace:
func (m *Mobile) CreateSpace(name string) *C.char {
    if m.app == nil {
        return C.CString(toJSON(Error{Message: "not initialized"}))
    }

    app := (*Application)(m.app)
    info, err := app.CreateSpace(name)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    return C.CString(toJSON(map[string]interface{}{
        "spaceId":    info.SpaceID,
        "validUntil": info.ValidUntil,
        "status":     "created",
    }))
}

// Update Shutdown:
func (m *Mobile) Shutdown() *C.char {
    if m.app != nil {
        app := (*Application)(m.app)
        app.Close()
        m.app = nil
    }
    return C.CString(toJSON(map[string]string{"status": "ok"}))
}

// Add import at top:
import "context"
```

**Step 3: Write test**

Create `lib/anyfile/app_test.go`:
```go
package anyfile

import (
    "testing"
)

func TestApplication_Init(t *testing.T) {
    tmpDir := t.TempDir()

    config := &Config{
        AccountDir: tmpDir,
        // Don't connect to any services for this test
        CoordinatorAddr: "",
        FilenodeAddr:    "",
        ConsensusAddr:   "",
    }

    app, err := NewApplication(config)
    if err != nil {
        t.Fatalf("NewApplication failed: %v", err)
    }

    if app.PeerID() == "" {
        t.Error("PeerID should not be empty")
    }

    app.Close()
}

func TestApplication_CreateSpace_NoServices(t *testing.T) {
    tmpDir := t.TempDir()

    config := &Config{
        AccountDir: tmpDir,
        // Without services, space creation will fail gracefully
        CoordinatorAddr: "",
        FilenodeAddr:    "",
        ConsensusAddr:   "",
    }

    app, err := NewApplication(config)
    if err != nil {
        t.Fatalf("NewApplication failed: %v", err)
    }
    defer app.Close()

    _, err = app.CreateSpace("test-space")
    // Should fail because no coordinator
    if err == nil {
        t.Error("Expected error when creating space without coordinator")
    }
}
```

**Step 4: Run tests**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile -v
```

Expected: Tests pass

**Step 5: Commit**

Run:
```bash
git add lib/anyfile/
git commit -m "feat(app): add main application with space management"
```

---

## Phase 3: File Sync - Filenode Integration

### Task 3.1: Create filenode client

**Files:**
- Create: `lib/anyfile/filenode/client.go`

**Step 1: Write test for filenode operations**

Create `lib/anyfile/filenode/client_test.go`:
```go
package filenode

import (
    "context"
    "testing"
)

func TestClient_BlockPush(t *testing.T) {
    // Integration test - requires running filenode
    t.Skip("requires running filenode service")
}

func TestClient_BlockGet(t *testing.T) {
    t.Skip("requires running filenode service")
}
```

**Step 2: Implement filenode client**

Create `lib/anyfile/filenode/client.go`:
```go
package filenode

import (
    "context"
    "fmt"
    "net"

    "github.com/anyproto/any-sync/commonfile/fileproto"
    "storj.io/drpc/drpcconn"
)

// Client handles filenode communication
type Client struct {
    addr   string
    conn   net.Conn
    drpc   drpc.Conn
    client fileproto.DRPCFileClient
}

// NewClient creates a new filenode client
func NewClient(addr string) *Client {
    return &Client{addr: addr}
}

// Connect establishes connection to filenode
func (c *Client) Connect(ctx context.Context) error {
    var d net.Dialer
    conn, err := d.DialContext(ctx, "tcp", c.addr)
    if err != nil {
        return fmt.Errorf("failed to connect to filenode: %w", err)
    }

    c.conn = conn
    c.drpc = drpcconn.New(conn)
    c.client = fileproto.NewDRPCFileClient(c.drpc)

    return nil
}

// Close closes the connection
func (c *Client) Close() error {
    if c.drpc != nil {
        c.drpc.Close()
    }
    if c.conn != nil {
        return c.conn.Close()
    }
    return nil
}

// BlockPush pushes a block to filenode
func (c *Client) BlockPush(ctx context.Context, spaceID, fileID string, cid, data []byte) error {
    if c.client == nil {
        return fmt.Errorf("not connected")
    }

    req := &fileproto.BlockPushRequest{
        SpaceId: spaceID,
        FileId:  fileID,
        Cid:     cid,
        Data:    data,
    }

    _, err := c.client.BlockPush(ctx, req)
    return err
}

// BlockGet retrieves a block from filenode
func (c *Client) BlockGet(ctx context.Context, spaceID string, cid []byte) ([]byte, error) {
    if c.client == nil {
        return nil, fmt.Errorf("not connected")
    }

    req := &fileproto.BlockGetRequest{
        SpaceId: spaceID,
        Cid:     cid,
    }

    resp, err := c.client.BlockGet(ctx, req)
    if err != nil {
        return nil, err
    }

    return resp.Data, nil
}

// BlocksCheck checks if blocks exist
func (c *Client) BlocksCheck(ctx context.Context, spaceID string, cids [][]byte) (map[string]bool, error) {
    if c.client == nil {
        return nil, fmt.Errorf("not connected")
    }

    req := &fileproto.BlocksCheckRequest{
        SpaceId: spaceID,
        Cids:    cids,
    }

    resp, err := c.client.BlocksCheck(ctx, req)
    if err != nil {
        return nil, err
    }

    result := make(map[string]bool)
    for _, cid := range cids {
        cidStr := string(cid)
        result[cidStr] = false
    }

    for _, exists := range resp.Exists {
        result[exists.Cid] = true
    }

    return result, nil
}
```

**Step 3: Write integration test**

Create `lib/anyfile/filenode/integration_test.go`:
```go
//go:build integration
// +build integration

package filenode

import (
    "context"
    "testing"
    "time"
)

func TestClient_Integration(t *testing.T) {
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()

    client := NewClient("127.0.0.1:1005")

    err := client.Connect(ctx)
    if err != nil {
        t.Skipf("Filenode not available: %v", err)
    }
    defer client.Close()

    t.Log("Connected to filenode")

    // Test block push
    testCID := []byte("test-cid")
    testData := []byte("test data")

    err = client.BlockPush(ctx, "test-space", "test-file", testCID, testData)
    if err != nil {
        t.Fatalf("BlockPush failed: %v", err)
    }

    // Test block get
    data, err := client.BlockGet(ctx, "test-space", testCID)
    if err != nil {
        t.Fatalf("BlockGet failed: %v", err)
    }

    if string(data) != string(testData) {
        t.Errorf("Data mismatch: got %s, want %s", string(data), string(testData))
    }

    // Test blocks check
    exists, err := client.BlocksCheck(ctx, "test-space", [][]byte{testCID})
    if err != nil {
        t.Fatalf("BlocksCheck failed: %v", err)
    }

    if !exists[string(testCID)] {
        t.Error("Expected CID to exist")
    }
}
```

**Step 4: Run integration test**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile/filenode -v -tags=integration
```

Expected: Either SKIP or PASS if filenode running

**Step 5: Commit**

Run:
```bash
git add lib/anyfile/filenode/
git commit -m "feat(filenode): add DRPC client for file operations"
```

### Task 3.2: Add file sync service

**Files:**
- Create: `lib/anyfile/sync/service.go`
- Create: `lib/anyfile/sync/hasher.go`

**Step 1: Create file hasher**

Create `lib/anyfile/sync/hasher.go`:
```go
package sync

import (
    "crypto/sha256"
    "encoding/hex"
    "hash"
    "io"
    "os"
)

// Hasher calculates file hashes
type Hasher struct {
    hashFunc hash.Hash
}

// NewHasher creates a new hasher
func NewHasher() *Hasher {
    return &Hasher{
        hashFunc: sha256.New(),
    }
}

// HashFile calculates hash of file
func (h *Hasher) HashFile(path string) (string, error) {
    f, err := os.Open(path)
    if err != nil {
        return "", err
    }
    defer f.Close()

    h.hashFunc.Reset()
    if _, err := io.Copy(h.hashFunc, f); err != nil {
        return "", err
    }

    return hex.EncodeToString(h.hashFunc.Sum(nil)), nil
}

// HashBytes calculates hash of bytes
func (h *Hasher) HashBytes(data []byte) string {
    h.hashFunc.Reset()
    h.hashFunc.Write(data)
    return hex.EncodeToString(h.hashFunc.Sum(nil))
}
```

**Step 2: Create sync service**

Create `lib/anyfile/sync/service.go`:
```go
package sync

import (
    "context"
    "fmt"
    "os"
    "path/filepath"

    "github.com/anyproto/any-file-mobile/anyfile/filenode"
    "github.com/anyproto/any-file-mobile/anyfile/space"
    "go.uber.org/zap"
)

// Service handles file synchronization
type Service struct {
    spaceSvc  *space.Service
    filenode  *filenode.Client
    hasher    *Hasher
    logger    *zap.Logger
}

// NewService creates a new sync service
func NewService(
    spaceSvc *space.Service,
    filenode *filenode.Client,
    logger *zap.Logger,
) *Service {
    return &Service{
        spaceSvc: spaceSvc,
        filenode: filenode,
        hasher:   NewHasher(),
        logger:   logger,
    }
}

// UploadFile uploads a file to filenode
func (s *Service) UploadFile(ctx context.Context, spaceID, filePath string) (*UploadResult, error) {
    s.logger.Info("Uploading file",
        zap.String("spaceId", spaceID),
        zap.String("path", filePath),
    )

    // Read file
    data, err := os.ReadFile(filePath)
    if err != nil {
        return nil, fmt.Errorf("failed to read file: %w", err)
    }

    // Calculate hash
    cid := s.hasher.HashBytes(data)

    // Get space credentials
    storedSpace, ok := s.spaceSvc.Storage().Get(spaceID)
    if !ok {
        return nil, fmt.Errorf("space not found: %s", spaceID)
    }

    // Push to filenode
    fileName := filepath.Base(filePath)
    if err := s.filenode.BlockPush(ctx, storedSpace.SpaceID, fileName, []byte(cid), data); err != nil {
        return nil, fmt.Errorf("failed to upload to filenode: %w", err)
    }

    s.logger.Info("File uploaded",
        zap.String("spaceId", spaceID),
        zap.String("file", fileName),
        zap.String("cid", cid),
    )

    return &UploadResult{
        CID:      cid,
        Size:     int64(len(data)),
        FileName: fileName,
        SpaceID:  spaceID,
    }, nil
}

// DownloadFile downloads a file from filenode
func (s *Service) DownloadFile(ctx context.Context, spaceID, cid, destPath string) error {
    s.logger.Info("Downloading file",
        zap.String("spaceId", spaceID),
        zap.String("cid", cid),
        zap.String("dest", destPath),
    )

    // Get from filenode
    data, err := s.filenode.BlockGet(ctx, spaceID, []byte(cid))
    if err != nil {
        return fmt.Errorf("failed to download from filenode: %w", err)
    }

    // Write to file
    if err := os.WriteFile(destPath, data, 0644); err != nil {
        return fmt.Errorf("failed to write file: %w", err)
    }

    s.logger.Info("File downloaded",
        zap.String("spaceId", spaceID),
        zap.String("cid", cid),
        zap.Int64("size", int64(len(data))),
    )

    return nil
}

// UploadResult contains upload result
type UploadResult struct {
    CID      string
    Size     int64
    FileName string
    SpaceID  string
}
```

**Step 3: Add storage getter to space service**

Modify `lib/anyfile/space/service.go`:
```go
// Add method to Service:
func (s *Service) Storage() *Storage {
    return s.storage
}
```

**Step 4: Write test**

Create `lib/anyfile/sync/service_test.go`:
```go
package sync

import (
    "context"
    "testing"
)

func TestService_UploadFile(t *testing.T) {
    // Requires running filenode
    t.Skip("requires running filenode service")
}

func TestHasher(t *testing.T) {
    hasher := NewHasher()

    data := []byte("test data")
    hash1 := hasher.HashBytes(data)
    hash2 := hasher.HashBytes(data)

    if hash1 != hash2 {
        t.Error("Hash should be consistent")
    }

    if hash1 == "" {
        t.Error("Hash should not be empty")
    }
}
```

**Step 5: Run tests**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test ./anyfile/sync -v
```

Expected: Hasher test passes, upload test skipped

**Step 6: Commit**

Run:
```bash
git add lib/anyfile/sync/
git commit -m "feat(sync): add file upload/download with filenode integration"
```

---

## Phase 4: Android Layer - Middleware Bridge

### Task 4.1: Create protobuf definitions

**Files:**
- Create: `lib/proto/event.proto`
- Create: `lib/proto/Makefile`

**Step 1: Create event protobuf**

Create `lib/proto/event.proto`:
```protobuf
syntax = "proto3";

package anyfile;

option go_package = "github.com/anyproto/any-file-mobile/proto";

// Event from Go to Kotlin
message Event {
    repeated Message messages = 1;
}

message Message {
    oneof payload {
        SpaceCreatedEvent space_created = 1;
        SpaceStatusEvent space_status = 2;
        FileSyncedEvent file_synced = 3;
        ErrorEvent error = 4;
        P2PStatusEvent p2p_status = 5;
    }
}

// Space created event
message SpaceCreatedEvent {
    string space_id = 1;
    string space_name = 2;
    uint64 valid_until = 3;
}

// Space status update
message SpaceStatusEvent {
    string space_id = 1;
    string status = 2; // "active", "expired", "pending"
}

// File synced event
message FileSyncedEvent {
    string space_id = 1;
    string file_name = 2;
    string cid = 3;
    int64 size = 4;
    string status = 5; // "uploaded", "downloaded", "syncing", "error"
}

// Error event
message ErrorEvent {
    string code = 1;
    string message = 2;
}

// P2P status event
message P2PStatusEvent {
    string space_id = 1;
    string status = 2; // "connected", "disconnected", "connecting"
    int32 peer_count = 3;
}
```

**Step 2: Generate Go code**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
protoc --go_out=. --go_opt=paths=source_relative proto/event.proto
```

Expected: `proto/event.pb.go` created

**Step 3: Commit**

Run:
```bash
git add lib/proto/
git commit -m "feat(proto): add event definitions for Go-Kotlin communication"
```

### Task 4.2: Update mobile interface with sync methods

**Files:**
- Modify: `lib/anyfile/mobile.go`
- Modify: `lib/anyfile/app.go`

**Step 1: Add sync methods to Application**

Modify `lib/anyfile/app.go`:
```go
// Add to Application struct:
type Application struct {
    // ... existing fields ...
    filenode  *filenode.Client
    syncSvc   *sync.Service
}

// Update NewApplication to initialize filenode and sync:
func NewApplication(config *Config) (*Application, error) {
    // ... existing code ...

    // Initialize filenode client
    if config.FilenodeAddr != "" {
        app.filenode = filenode.NewClient(config.FilenodeAddr)
    }

    // Initialize sync service
    if app.filenode != nil {
        app.syncSvc = sync.NewService(
            app.spaceSvc,
            app.filenode,
            logger,
        )
    }

    // ... rest of existing code ...
}

// Update Start to connect to filenode:
func (a *Application) Start(ctx context.Context) error {
    // ... existing code ...

    // Connect to filenode
    if a.filenode != nil {
        if err := a.filenode.Connect(ctx); err != nil {
            a.logger.Warn("Failed to connect to filenode", zap.Error(err))
        } else {
            a.logger.Info("Connected to filenode")
        }
    }

    // ... rest of existing code ...
}

// Add sync methods:
func (a *Application) UploadFile(spaceID, filePath string) (*sync.UploadResult, error) {
    a.mu.RLock()
    defer a.mu.RUnlock()

    if !a.started {
        return nil, fmt.Errorf("application not started")
    }

    return a.syncSvc.UploadFile(context.Background(), spaceID, filePath)
}

func (a *Application) DownloadFile(spaceID, cid, destPath string) error {
    a.mu.RLock()
    defer a.mu.RUnlock()

    if !a.started {
        return fmt.Errorf("application not started")
    }

    return a.syncSvc.DownloadFile(context.Background(), spaceID, cid, destPath)
}
```

**Step 2: Add mobile exports**

Modify `lib/anyfile/mobile.go`:
```go
// Add new exports:

//export UploadFile
func (m *Mobile) UploadFile(spaceID, filePath string) *C.char {
    if m.app == nil {
        return C.CString(toJSON(Error{Message: "not initialized"}))
    }

    app := (*Application)(m.app)
    result, err := app.UploadFile(spaceID, filePath)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    return C.CString(toJSON(result))
}

//export DownloadFile
func (m *Mobile) DownloadFile(spaceID, cid, destPath string) *C.char {
    if m.app == nil {
        return C.CString(toJSON(Error{Message: "not initialized"}))
    }

    app := (*Application)(m.app)
    err := app.DownloadFile(spaceID, cid, destPath)
    if err != nil {
        return C.CString(toJSON(Error{Message: err.Error()}))
    }

    return C.CString(toJSON(map[string]string{"status": "ok"}))
}

//export ListSpaces
func (m *Mobile) ListSpaces() *C.char {
    if m.app == nil {
        return C.CString(toJSON(Error{Message: "not initialized"}))
    }

    app := (*Application)(m.app)
    spaces := app.ListSpaces()

    return C.CString(toJSON(spaces))
}
```

**Step 3: Rebuild AAR**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
./build.sh
```

**Step 4: Commit**

Run:
```bash
git add lib/anyfile/
git commit -m "feat(mobile): add file upload/download and list spaces methods"
```

### Task 4.3: Create Android middleware service

**Files:**
- Create: `app/src/main/java/com/anyfile/middleware/MiddlewareService.kt`
- Create: `app/src/main/java/com/anyfile/middleware/EventDispatcher.kt`

**Step 1: Create data models**

Create `app/src/main/java/com/anyfile/data/Models.kt`:
```kotlin
package com.anyfile.data

// Space info
data class SpaceInfo(
    val spaceId: String,
    val spaceName: String,
    val status: String = "active",
    val validUntil: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// Upload result
data class FileUploadResult(
    val cid: String,
    val size: Long,
    val fileName: String,
    val spaceId: String
)

// Configuration
data class AppConfiguration(
    val accountDir: String,
    val coordinatorAddr: String = "10.0.2.2:1004",  // Android emulator
    val filenodeAddr: String = "10.0.2.2:1005",
    val consensusAddr: String = "10.0.2.2:1006",
    val enableMDNS: Boolean = true,
    val enableDHT: Boolean = true
)

// Error
data class AppError(
    val message: String,
    val code: String = ""
)

// Events
sealed class Event {
    data class SpaceCreated(val spaceInfo: SpaceInfo) : Event()
    data class SpaceStatusChanged(val spaceId: String, val status: String) : Event()
    data class FileSynced(val result: FileUploadResult) : Event()
    data class Error(val error: AppError) : Event()
    data class P2PStatusChanged(val spaceId: String, val status: String, val peerCount: Int) : Event()
}
```

**Step 2: Create middleware service interface**

Create `app/src/main/java/com/anyfile/middleware/MiddlewareService.kt`:
```kotlin
package com.anyfile.middleware

import com.anyfile.data.*
import com.google.gson.Gson
import goanyfile.AnyFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class MiddlewareService @Inject constructor(
    private val gson: Gson
) {
    private var mobile: AnyFile? = null
    private val _events = MutableSharedFlow<Event>()
    val events: Flow<Event> = _events

    val isInitialized: Boolean
        get() = mobile != null

    /**
     * Initialize the mobile library
     */
    suspend fun init(config: AppConfiguration): Result<Unit> {
        return suspendCancellableCoroutine { cont ->
            try {
                val mobile = AnyFile()

                val configJson = gson.toJson(mapOf(
                    "accountDir" to config.accountDir,
                    "coordinatorAddr" to config.coordinatorAddr,
                    "filenodeAddr" to config.filenodeAddr,
                    "consensusAddr" to config.consensusAddr,
                    "enableMDNS" to config.enableMDNS,
                    "enableDHT" to config.enableDHT
                ))

                val resultJson = mobile.init(configJson)
                val result = gson.fromJson(resultJson, Map::class.java)

                if (result["status"] == "ok") {
                    this.mobile = mobile
                    Timber.i("Middleware initialized successfully")
                    Timber.i("Peer ID: ${result["peerId"]}")
                    cont.resume(Result.success(Unit))
                } else {
                    val error = result["error"]?.toString() ?: "Unknown error"
                    cont.resume(Result.failure(Exception(error)))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize middleware")
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * Create a new space
     */
    suspend fun createSpace(name: String): Result<SpaceInfo> {
        return suspendCancellableCoroutine { cont ->
            try {
                val mobile = mobile ?: throw IllegalStateException("Not initialized")

                val resultJson = mobile.createSpace(name)
                val result = gson.fromJson(resultJson, Map::class.java)

                val error = result["error"]
                if (error != null) {
                    cont.resume(Result.failure(Exception(error.toString())))
                    return@suspendCancellableCoroutine
                }

                val spaceInfo = SpaceInfo(
                    spaceId = result["spaceId"]?.toString() ?: "",
                    spaceName = name,
                    validUntil = (result["validUntil"] as? Double)?.toLong() ?: 0
                )

                // Emit event
                _events.tryEmit(Event.SpaceCreated(spaceInfo))

                cont.resume(Result.success(spaceInfo))
            } catch (e: Exception) {
                Timber.e(e, "Failed to create space")
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * List all spaces
     */
    suspend fun listSpaces(): Result<List<SpaceInfo>> {
        return suspendCancellableCoroutine { cont ->
            try {
                val mobile = mobile ?: throw IllegalStateException("Not initialized")

                val resultJson = mobile.listSpaces()
                val result = gson.fromJson(resultJson, Map::class.java)

                val error = result["error"]
                if (error != null) {
                    cont.resume(Result.failure(Exception(error.toString())))
                    return@suspendCancellableCoroutine
                }

                // Parse spaces list
                val spacesList = result["spaces"] as? List<*> ?: emptyList<Any>()
                val spaces = spacesList.mapNotNull { item ->
                    item as? Map<*, *>
                    SpaceInfo(
                        spaceId = item["spaceId"]?.toString() ?: return@mapNotNull null,
                        spaceName = item["spaceName"]?.toString() ?: "",
                        status = item["status"]?.toString() ?: "active",
                        validUntil = (item["validUntil"] as? Double)?.toLong() ?: 0,
                        createdAt = (item["createdAt"] as? Double)?.toLong() ?: 0
                    )
                }

                cont.resume(Result.success(spaces))
            } catch (e: Exception) {
                Timber.e(e, "Failed to list spaces")
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * Upload a file
     */
    suspend fun uploadFile(spaceId: String, filePath: String): Result<FileUploadResult> {
        return suspendCancellableCoroutine { cont ->
            try {
                val mobile = mobile ?: throw IllegalStateException("Not initialized")

                val resultJson = mobile.uploadFile(spaceId, filePath)
                val result = gson.fromJson(resultJson, Map::class.java)

                val error = result["error"]
                if (error != null) {
                    cont.resume(Result.failure(Exception(error.toString())))
                    return@suspendCancellableCoroutine
                }

                val uploadResult = FileUploadResult(
                    cid = result["cid"]?.toString() ?: "",
                    size = (result["size"] as? Double)?.toLong() ?: 0,
                    fileName = result["fileName"]?.toString() ?: "",
                    spaceId = result["spaceId"]?.toString() ?: spaceId
                )

                // Emit event
                _events.tryEmit(Event.FileSynced(uploadResult))

                cont.resume(Result.success(uploadResult))
            } catch (e: Exception) {
                Timber.e(e, "Failed to upload file")
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * Shutdown the middleware
     */
    suspend fun shutdown(): Result<Unit> {
        return suspendCancellableCoroutine { cont ->
            try {
                mobile?.shutdown()
                mobile = null
                Timber.i("Middleware shutdown complete")
                cont.resume(Result.success(Unit))
            } catch (e: Exception) {
                Timber.e(e, "Failed to shutdown middleware")
                cont.resumeWithException(e)
            }
        }
    }

    fun getPeerID(): String? {
        return mobile?.getPeerID()
    }
}
```

**Step 3: Update repository to use middleware**

Modify `app/src/main/java/com/anyfile/data/AnyFileRepository.kt`:
```kotlin
@Singleton
class AnyFileRepository @Inject constructor(
    private val middleware: MiddlewareService,
    private val preferences: LocalPreferences,
    private val context: Context
) {
    private val TAG = "AnyFileRepository"

    // Event flow from middleware
    val events: Flow<Event> = middleware.events

    /**
     * Initialize the repository
     */
    suspend fun initialize(): Result<Unit> {
        return try {
            val config = AppConfiguration(
                accountDir = context.filesDir.absolutePath + "/anyfile",
                coordinatorAddr = preferences.coordinatorAddress.first() ?: "10.0.2.2:1004",
                filenodeAddr = preferences.filenodeAddress.first() ?: "10.0.2.2:1005",
                consensusAddr = "10.0.2.2:1006"
            )

            middleware.init(config)

            Timber.i("Repository initialized successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize repository")
            Result.failure(e)
        }
    }

    /**
     * Create a new space
     */
    suspend fun createSpace(name: String): Result<SpaceInfo> {
        return middleware.createSpace(name)
    }

    /**
     * List all spaces
     */
    suspend fun listSpaces(): Result<List<SpaceInfo>> {
        return middleware.listSpaces()
    }

    /**
     * Upload a file
     */
    suspend fun uploadFile(spaceId: String, filePath: String): Result<FileUploadResult> {
        return middleware.uploadFile(spaceId, filePath)
    }

    /**
     * Get peer ID
     */
    fun getPeerID(): String? {
        return middleware.getPeerID()
    }

    /**
     * Shutdown
     */
    suspend fun shutdown() {
        middleware.shutdown()
    }
}
```

**Step 4: Commit**

Run:
```bash
git add app/src/main/java/com/anyfile/
git commit -m "feat(android): add middleware service and repository layer"
```

---

## Phase 5: UI Implementation

### Task 5.1: Update SpacesScreen with real data

**Files:**
- Modify: `app/src/main/java/com/anyfile/ui/spaces/SpacesScreen.kt`
- Modify: `app/src/main/java/com/anyfile/viewmodel/SpacesViewModel.kt`

**Step 1: Update ViewModel**

Modify `app/src/main/java/com/anyfile/viewmodel/SpacesViewModel.kt`:
```kotlin
@HiltViewModel
class SpacesViewModel @Inject constructor(
    private val repository: AnyFileRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<SpacesUiState>(SpacesUiState.Loading)
    val uiState: StateFlow<SpacesUiState> = _uiState.asStateFlow()

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    init {
        loadSpaces()
    }

    fun refresh() {
        loadSpaces()
    }

    private fun loadSpaces() {
        viewModelScope.launch {
            _uiState.value = SpacesUiState.Loading

            when (val result = repository.listSpaces()) {
                is Result.Success -> {
                    _uiState.value = SpacesUiState.Success(result.data)
                }
                is Result.Failure -> {
                    _uiState.value = SpacesUiState.Error(
                        result.exception.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    fun showCreateSpaceDialog() {
        _showCreateDialog.value = true
    }

    fun hideCreateSpaceDialog() {
        _showCreateDialog.value = false
    }

    fun createSpace(name: String) {
        viewModelScope.launch {
            _showCreateDialog.value = false
            _uiState.value = SpacesUiState.Loading

            when (val result = repository.createSpace(name)) {
                is Result.Success -> {
                    loadSpaces() // Refresh list
                }
                is Result.Failure -> {
                    _uiState.value = SpacesUiState.Error(
                        result.exception.message ?: "Failed to create space"
                    )
                }
            }
        }
    }

    fun deleteSpace(spaceId: String) {
        viewModelScope.launch {
            // TODO: implement delete
        }
    }
}
```

**Step 2: Update SpaceInfo data model**

Modify `app/src/main/java/com/anyfile/data/SpaceInfo.kt`:
```kotlin
data class SpaceInfo(
    val id: String,
    val name: String,
    val status: SpaceStatus = SpaceStatus.Active,
    val fileCount: Int = 0,
    val totalSize: Long = 0,
    val createdAt: Long = 0,
    val validUntil: Long = 0
)

enum class SpaceStatus {
    Active,
    Expired,
    Pending,
    Error
}
```

**Step 3: Commit**

Run:
```bash
git add app/src/main/java/com/anyfile/
git commit -m "feat(ui): connect SpacesScreen to real middleware"
```

### Task 5.2: Update FilesScreen with sync functionality

**Files:**
- Modify: `app/src/main/java/com/anyfile/ui/files/FilesScreen.kt`
- Modify: `app/src/main/java/com/anyfile/viewmodel/FilesViewModel.kt`

**Step 1: Add file picker intent**

Modify `app/src/main/java/com/anyfile/ui/files/FilesScreen.kt`:
```kotlin
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.uploadFile(context, it) }
    }

    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload File")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is FilesUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is FilesUiState.Success -> {
                    // Show upload status or empty state
                    if (state.uploadResults.isEmpty()) {
                        EmptyFilesMessage(onUploadClick = {
                            filePickerLauncher.launch("*/*")
                        })
                    } else {
                        FilesList(
                            results = state.uploadResults,
                            onDeleteClick = { /* TODO */ }
                        )
                    }
                }
                is FilesUiState.Error -> {
                    ErrorMessage(
                        message = state.message,
                        onRetry = { viewModel.refresh() }
                    )
                }
            }
        }
    }
}
```

**Step 2: Update FilesViewModel**

Modify `app/src/main/java/com/anyfile/viewmodel/FilesViewModel.kt`:
```kotlin
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: AnyFileRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val spaceId: String = checkNotNull(savedStateHandle["spaceId"])

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        loadFiles()
    }

    private fun loadFiles() {
        viewModelScope.launch {
            _uiState.value = FilesUiState.Success(emptyList())
        }
    }

    fun refresh() {
        loadFiles()
    }

    fun uploadFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                // Copy file to app storage
                val tempFile = copyUriToTempFile(context, uri)

                when (val result = repository.uploadFile(spaceId, tempFile.absolutePath)) {
                    is Result.Success -> {
                        _uiState.value = FilesUiState.Success(
                            (uiState.value as? FilesUiState.Success)?.uploadResults ?: emptyList()
                        )
                        Toast.makeText(
                            context,
                            "File uploaded: ${result.data.fileName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is Result.Failure -> {
                        _uiState.value = FilesUiState.Error(
                            result.exception.message ?: "Upload failed"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = FilesUiState.Error(e.message ?: "Upload failed")
            }
        }
    }

    private fun copyUriToTempFile(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file")

        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}")
        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }
}

sealed class FilesUiState {
    object Loading : FilesUiState()
    data class Success(val uploadResults: List<FileUploadResult>) : FilesUiState()
    data class Error(val message: String) : FilesUiState()
}
```

**Step 3: Commit**

Run:
```bash
git add app/src/main/java/com/anyfile/
git commit -m "feat(ui): add file upload functionality to FilesScreen"
```

---

## Phase 6: Testing and E2E

### Task 6.1: Create E2E test scenario

**Files:**
- Create: `lib/e2e_test.go`

**Step 1: Create E2E test**

Create `lib/e2e_test.go`:
```go
//go:build e2e
// +build e2e

package anyfile_test

import (
    "context"
    "io/ioutil"
    "os"
    "path/filepath"
    "testing"
    "time"
)

func TestE2E_CreateAndSyncSpace(t *testing.T) {
    if testing.Short() {
        t.Skip("Skipping E2E test in short mode")
    }

    // Requires running any-sync services
    // This test demonstrates the full flow

    tmpDir, err := ioutil.TempDir("", "anyfile-e2e-*")
    if err != nil {
        t.Fatal(err)
    }
    defer os.RemoveAll(tmpDir)

    // Create config pointing to local services
    configJSON := `{
        "accountDir": "` + tmpDir + `",
        "coordinatorAddr": "127.0.0.1:1004",
        "filenodeAddr": "127.0.0.1:1005",
        "consensusAddr": "127.0.0.1:1006"
    }`

    t.Log("Starting E2E test")
    t.Logf("Using account dir: %s", tmpDir)

    // Step 1: Initialize mobile
    mobile := NewMobile()
    result := mobile.Init(configJSON)
    if result.Error() != nil {
        t.Skipf("Services not available: %v", result.Error())
    }
    defer mobile.Shutdown()

    // Get peer ID
    peerID := mobile.GetPeerID()
    t.Logf("Peer ID: %s", peerID)

    // Step 2: Create a space
    spaceResult := mobile.CreateSpace("test-space")
    if spaceResult.Error() != nil {
        t.Fatalf("CreateSpace failed: %v", spaceResult.Error())
    }

    t.Logf("Space created successfully")

    // Step 3: Create a test file
    testFile := filepath.Join(tmpDir, "test.txt")
    testContent := []byte("Hello, any-file!")
    if err := os.WriteFile(testFile, testContent, 0644); err != nil {
        t.Fatal(err)
    }

    // Step 4: Upload file
    uploadResult := mobile.UploadFile(spaceResult.SpaceID(), testFile)
    if uploadResult.Error() != nil {
        t.Fatalf("UploadFile failed: %v", uploadResult.Error())
    }

    t.Logf("File uploaded: CID=%s", uploadResult.CID())

    // Step 5: Download file to different location
    downloadPath := filepath.Join(tmpDir, "downloaded.txt")
    downloadResult := mobile.DownloadFile(spaceResult.SpaceID(), uploadResult.CID(), downloadPath)
    if downloadResult.Error() != nil {
        t.Fatalf("DownloadFile failed: %v", downloadResult.Error())
    }

    // Step 6: Verify file content
    downloadedContent, err := os.ReadFile(downloadPath)
    if err != nil {
        t.Fatalf("Failed to read downloaded file: %v", err)
    }

    if string(downloadedContent) != string(testContent) {
        t.Errorf("Content mismatch: got %s, want %s", string(downloadedContent), string(testContent))
    }

    t.Log("E2E test completed successfully")
}
```

**Step 2: Run E2E test**

Run:
```bash
cd ~/projects/anyproto/any-file-android/lib
go test -v -tags=e2e -timeout 5m ./e2e_test.go
```

Expected: Either SKIP if services not running, or PASS

**Step 3: Commit**

Run:
```bash
git add lib/e2e_test.go
git commit -m "test(e2e): add end-to-end test for space creation and file sync"
```

### Task 6.2: Documentation

**Files:**
- Create: `docs/ANDROID_DEVELOPMENT.md`
- Modify: `README.md`

**Step 1: Create Android development guide**

Create `docs/ANDROID_DEVELOPMENT.md`:
```markdown
# Android Development Guide

## Prerequisites

1. **Go 1.24+**
   ```bash
   brew install go
   ```

2. **gomobile**
   ```bash
   go install golang.org/x/mobile/cmd/gomobile@latest
   gomobile init
   ```

3. **Android SDK** (via Android Studio)

4. **Android NDK** (for gomobile)

## Building

### 1. Build Go Library

```bash
cd lib
./build.sh
```

### 2. Build Android App

```bash
./gradlew assembleDebug
```

### 3. Install on Device

```bash
./gradlew installDebug
```

## Development Workflow

### Modifying Go Code

1. Edit files in `lib/anyfile/`
2. Rebuild: `cd lib && ./build.sh`
3. Rebuild app: `./gradlew assembleDebug`

### Modifying Kotlin Code

1. Edit files in `app/src/main/java/com/anyfile/`
2. Rebuild: `./gradlew assembleDebug`

## Running Tests

### Unit Tests (Go)
```bash
cd lib
go test ./...
```

### Unit Tests (Kotlin)
```bash
./gradlew test
```

### Integration Tests

Requires running any-sync services:
```bash
cd docker
docker-compose up -d

# Run tests
cd lib
go test ./... -tags=integration -v
```

### E2E Tests

```bash
cd lib
go test -v -tags=e2e -timeout 10m ./e2e_test.go
```

## any-sync Infrastructure

### Local Development Setup

The app connects to local any-sync services by default:

- Coordinator: `127.0.0.1:1004`
- Filenode: `127.0.0.1:1005`
- Consensus: `127.0.0.1:1006`

### Starting Services

```bash
cd ../any-file/docker
docker-compose up -d
```

### Verify Services

```bash
# Check coordinator
curl http://127.0.0.1:1004

# Check filenode
curl http://127.0.0.1:1005
```

## Troubleshooting

### Build Failures

**gomobile not found**
```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

**NDK not found**
- Install via Android Studio: Preferences → Appearance → Behavior → System Settings → Android SDK → SDK Tools → NDK

### Runtime Issues

**Connection refused**
- Verify any-sync services are running
- Check address configuration (use `10.0.2.2` for emulator)

**Space creation fails**
- Check coordinator logs
- Verify peer is registered in network configuration

## Architecture

See [docs/plans/2026-02-19-android-p2p-client.md](./plans/2026-02-19-android-p2p-client.md) for detailed architecture documentation.
```

**Step 2: Commit**

Run:
```bash
git add docs/
git commit -m "docs: add Android development guide"
```

---

## Summary

This implementation plan provides:

1. **Complete Go library** with gomobile bindings
2. **Space management** using any-sync infrastructure
3. **File sync** with filenode integration
4. **Android middleware layer** bridging Go and Kotlin
5. **UI implementation** with Jetpack Compose
6. **Testing strategy** with unit, integration, and E2E tests
7. **Documentation** for developers

**Total estimated implementation time: 6-8 weeks**

**Key dependencies:**
- any-sync infrastructure must be running for integration tests
- gomobile and Android NDK for building
- Android emulator or device for testing

**Next steps after implementation:**
1. Add P2P discovery with mDNS
2. Implement direct P2P file transfer
3. Add conflict resolution
4. Optimize battery usage
5. Add encryption for stored credentials
