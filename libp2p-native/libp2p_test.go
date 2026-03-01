package libp2p_native

import (
	"testing"

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
