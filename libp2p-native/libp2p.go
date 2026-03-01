// Package libp2p_native provides a gomobile-compatible wrapper around go-libp2p.
// This package will be compiled to an Android AAR library.
package libp2p_native

import (
	"encoding/binary"
	"errors"
	"crypto/ed25519"

	"github.com/mr-tron/base58"
)

// Peer represents a libp2p peer identity
type Peer struct {
	ID      string // Peer ID (base58-encoded)
	PrivKey []byte // Ed25519 private key (64 bytes, expanded)
	PubKey  []byte // Ed25519 public key (32 bytes)
}

// CreatePeer creates a new libp2p peer identity from Ed25519 private key bytes.
// The privKeyBytes must be exactly 32 bytes (Ed25519 seed).
func CreatePeer(privKeyBytes []byte) (*Peer, error) {
	if len(privKeyBytes) != 32 {
		return nil, errors.New("invalid private key length: must be 32 bytes")
	}

	// Ed25519 private key is first 32 bytes, public key is derived
	privKey := ed25519.NewKeyFromSeed(privKeyBytes)
	pubKey := privKey.Public().(ed25519.PublicKey)

	// Construct protobuf message for Ed25519 public key
	// Field 1 (varint): key_type = Ed25519 = 1
	// Field 2 (length-delimited): public_key (32 bytes)
	msg := []byte{0x08, 0x01, 0x12, 0x20} // key_type=1, field_2 with length=32
	msg = append(msg, pubKey...)

	// Construct identity multihash
	// Format: [0x00][varint(length)][protobuf_message]
	identityMH := []byte{0x00} // Identity multihash prefix
	varBuf := make([]byte, binary.MaxVarintLen64)
	varLen := binary.PutUvarint(varBuf, uint64(len(msg)))
	identityMH = append(identityMH, varBuf[:varLen]...)
	identityMH = append(identityMH, msg...)

	// Encode to base58
	peerID := base58.Encode(identityMH)

	return &Peer{
		ID:      peerID,
		PrivKey: []byte(privKey), // 64 bytes (Ed25519 expanded format)
		PubKey:  []byte(pubKey),  // 32 bytes
	}, nil
}
