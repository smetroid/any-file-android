// Package libp2p_native provides a gomobile-compatible wrapper around go-libp2p.
// This package will be compiled to an Android AAR library.
package libp2p_native

import (
	"crypto/ed25519"
	"errors"

	"github.com/mr-tron/base58"
	"github.com/multiformats/go-multihash"
	"github.com/multiformats/go-varint"
)

// Peer represents a libp2p peer identity
type Peer struct {
	ID      string // Peer ID (base58-encoded)
	PrivKey []byte // Ed25519 private key (32 bytes)
	PubKey  []byte // Ed25519 public key (32 bytes)
}

// CreatePeer creates a new libp2p peer identity from Ed25519 private key bytes.
// The privKeyBytes must be exactly 32 bytes (Ed25519 private key length).
func CreatePeer(privKeyBytes []byte) (*Peer, error) {
	if len(privKeyBytes) != 32 {
		return nil, errors.New("invalid private key length: must be 32 bytes")
	}

	// Ed25519 private key is first 32 bytes, public key is derived
	privKey := ed25519.NewKeyFromSeed(privKeyBytes)
	pubKey := privKey.Public().(ed25519.PublicKey)

	// Create multihash: [0x12, 0x20, <SHA-256 of pubkey>]
	// 0x12 = SHA-256, 0x20 = 32 bytes
	mh, err := multihash.Sum(pubKey, multihash.SHA2_256, -1)
	if err != nil {
		return nil, err
	}

	// Create peer ID from multihash with identity prefix
	// Format: [varint(len(mh)), mh_bytes...]
	buf := make([]byte, varint.UvarintSize(uint64(len(mh)))+len(mh))
	n := varint.PutUvarint(buf, uint64(len(mh)))
	copy(buf[n:], mh)

	// Encode to base58
	peerID := base58.Encode(buf)

	return &Peer{
		ID:      peerID,
		PrivKey: []byte(privKey),
		PubKey:  []byte(pubKey),
	}, nil
}
