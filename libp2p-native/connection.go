package libp2p_native

import (
	"context"
	"crypto/ed25519"
	"fmt"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	libp2ptls "github.com/libp2p/go-libp2p/p2p/security/tls"
	"github.com/multiformats/go-multiaddr"
)

// Connection represents a libp2p TCP+TLS connection
type Connection struct {
	stream network.Stream
}

// Dial establishes a libp2p connection with multistream + TLS.
func Dial(host string, port int, localPrivKey []byte, remotePeerID string) (*Connection, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Convert 32-byte seed to Ed25519 private key for libp2p
	privKey := ed25519.NewKeyFromSeed(localPrivKey)
	libp2pPrivKey, err := crypto.UnmarshalEd25519PrivateKey([]byte(privKey))
	if err != nil {
		return nil, fmt.Errorf("unmarshal private key: %w", err)
	}

	// Create libp2p host with TLS transport
	priv := libp2p.Identity(libp2pPrivKey)
	tlsOpt := libp2p.Security(libp2ptls.ID, libp2ptls.New)

	h, err := libp2p.New(
		priv,
		tlsOpt,
		libp2p.NoTransports,
		libp2p.NoListenAddrs,
	)
	if err != nil {
		return nil, fmt.Errorf("create libp2p host: %w", err)
	}
	defer h.Close()

	// Build multiaddr: /ip4/<host>/tcp/<port>/p2p/<peerID>
	maStr := fmt.Sprintf("/ip4/%s/tcp/%d", host, port)
	if remotePeerID != "" {
		maStr += fmt.Sprintf("/p2p/%s", remotePeerID)
	}

	ma, err := multiaddr.NewMultiaddr(maStr)
	if err != nil {
		return nil, fmt.Errorf("create multiaddr: %w", err)
	}

	// Parse peer info from address
	peerInfo, err := peer.AddrInfoFromP2pAddr(ma)
	if err != nil {
		return nil, fmt.Errorf("parse peer info: %w", err)
	}

	// Dial the peer
	err = h.Connect(ctx, *peerInfo)
	if err != nil {
		return nil, fmt.Errorf("connect to peer: %w", err)
	}

	// Open a stream (default protocol)
	stream, err := h.NewStream(ctx, peerInfo.ID)
	if err != nil {
		return nil, fmt.Errorf("open stream: %w", err)
	}

	return &Connection{stream: stream}, nil
}

// Read reads data from the connection
func (c *Connection) Read(p []byte) (n int, err error) {
	return c.stream.Read(p)
}

// Write writes data to the connection
func (c *Connection) Write(p []byte) (n int, err error) {
	return c.stream.Write(p)
}

// Close closes the connection
func (c *Connection) Close() error {
	return c.stream.Close()
}
