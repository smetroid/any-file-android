package libp2p_native

import (
	"testing"

	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/stretchr/testify/assert"
)

func TestCreatePeer(t *testing.T) {
	// Generate test Ed25519 key (32 random bytes for test)
	privKey := make([]byte, 32)
	for i := range privKey {
		privKey[i] = byte(i)
	}

	peer, err := CreatePeer(privKey)

	assert.NoError(t, err, "CreatePeer should not return error")
	assert.NotNil(t, peer, "Peer should not be nil")
	assert.NotEmpty(t, peer.ID, "Peer ID should not be empty")
	assert.Equal(t, 64, len(peer.PrivKey), "Private key should be 64 bytes (Ed25519 format)")
	assert.Equal(t, 32, len(peer.PubKey), "Public key should be 32 bytes")
}

func TestCreatePeerInvalidLength(t *testing.T) {
	invalidKey := make([]byte, 16) // Wrong length

	peer, err := CreatePeer(invalidKey)

	assert.Error(t, err, "Should return error for invalid key length")
	assert.Nil(t, peer, "Peer should be nil for invalid key")
}

func TestCreatePeerDeterministic(t *testing.T) {
	// Same input should produce same peer ID
	privKey := make([]byte, 32)
	for i := range privKey {
		privKey[i] = byte(42)
	}

	peer1, err1 := CreatePeer(privKey)
	peer2, err2 := CreatePeer(privKey)

	assert.NoError(t, err1)
	assert.NoError(t, err2)
	assert.Equal(t, peer1.ID, peer2.ID, "Same key should produce same peer ID")
	assert.Equal(t, peer1.PubKey, peer2.PubKey, "Same key should produce same public key")
}

func TestCreatePeerDifferentKeys(t *testing.T) {
	// Different inputs should produce different peer IDs
	privKey1 := make([]byte, 32)
	for i := range privKey1 {
		privKey1[i] = byte(1)
	}

	privKey2 := make([]byte, 32)
	for i := range privKey2 {
		privKey2[i] = byte(2)
	}

	peer1, _ := CreatePeer(privKey1)
	peer2, _ := CreatePeer(privKey2)

	assert.NotEqual(t, peer1.ID, peer2.ID, "Different keys should produce different peer IDs")
	assert.NotEqual(t, peer1.PubKey, peer2.PubKey, "Different keys should produce different public keys")
}

// TestCompatibilityWithGoLibp2p verifies that our peer IDs are compatible with go-libp2p
func TestCompatibilityWithGoLibp2p(t *testing.T) {
	seed := []byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31}

	// Create peer using our implementation
	ourPeer, err := CreatePeer(seed)
	assert.NoError(t, err, "CreatePeer should not return error")

	// Create peer using go-libp2p
	// Note: Ed25519 private key in libp2p is 64 bytes (seed + pubKey), not 32
	privKey, err := crypto.UnmarshalEd25519PrivateKey(ourPeer.PrivKey)
	assert.NoError(t, err, "Should unmarshal private key from our 64-byte format")

	libp2pID, err := peer.IDFromPrivateKey(privKey)
	assert.NoError(t, err, "Should create peer ID from private key")

	// Compare peer IDs - this is the critical compatibility test
	assert.Equal(t, ourPeer.ID, libp2pID.String(), "Peer ID must match go-libp2p format")

	t.Logf("Peer IDs match: %s", ourPeer.ID)
}

func TestDialLocalhost(t *testing.T) {
	t.Skip("Requires local libp2p server - will test in Android integration")

	privKey := make([]byte, 32)
	for i := range privKey {
		privKey[i] = byte(i)
	}

	conn, err := Dial("127.0.0.1", 1004, privKey, "")

	assert.NoError(t, err, "Dial should not return error")
	assert.NotNil(t, conn, "Connection should not be nil")
	if conn != nil {
		conn.Close()
	}
}
