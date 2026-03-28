const express = require('express');
const WebSocket = require('ws');
const http = require('http');
const cors = require('cors');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// Store active tunnels
const tunnels = new Map();
const payloads = new Map();

app.use(cors());
app.use(express.json());

// Health check endpoint
app.get('/', (req, res) => {
    res.json({
        status: 'online',
        version: '1.0.0',
        tunnels: tunnels.size,
        payloads: payloads.size,
        timestamp: new Date().toISOString()
    });
});

// API endpoint to get tunnel info
app.get('/api/tunnel/:id', (req, res) => {
    const tunnel = tunnels.get(req.params.id);
    if (tunnel) {
        res.json({ url: tunnel.url, port: tunnel.port, status: 'active' });
    } else {
        res.status(404).json({ error: 'Tunnel not found' });
    }
});

// WebSocket connection handler
wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const type = url.searchParams.get('type');
    const port = url.searchParams.get('port');
    const token = url.searchParams.get('token') || generateToken();

    console.log(`[${new Date().toISOString()}] New connection: ${type}, port: ${port}`);

    if (type === 'app') {
        // App connection (your Flutter app)
        tunnels.set(token, {
            ws,
            port,
            type: 'app',
            url: `https://${req.headers.host}/${token}`,
            connectedAt: new Date()
        });

        ws.send(JSON.stringify({
            type: 'registered',
            token: token,
            url: `https://${req.headers.host}/${token}`,
            message: 'Tunnel created! Your payload can connect to this URL'
        }));

        console.log(`✅ App tunnel created: ${token}`);

    } else if (type === 'payload') {
        // Payload connection
        const tunnelToken = url.searchParams.get('tunnel');
        const tunnel = tunnels.get(tunnelToken);

        if (tunnel) {
            payloads.set(tunnelToken, {
                ws,
                connectedAt: new Date(),
                tunnel: tunnel
            });

            ws.send(JSON.stringify({
                type: 'connected',
                tunnel: tunnelToken,
                message: 'Connected to tunnel'
            }));

            tunnel.ws.send(JSON.stringify({
                type: 'payload_connected',
                tunnel: tunnelToken,
                message: 'Payload has connected to your tunnel'
            }));

            console.log(`✅ Payload connected to tunnel: ${tunnelToken}`);

            // Relay messages between app and payload
            ws.on('message', (data) => {
                if (tunnel.ws.readyState === WebSocket.OPEN) {
                    tunnel.ws.send(data);
                }
            });

            tunnel.ws.on('message', (data) => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(data);
                }
            });

        } else {
            ws.send(JSON.stringify({
                type: 'error',
                message: 'Tunnel not found'
            }));
            ws.close();
            console.log(`❌ Invalid tunnel token: ${tunnelToken}`);
        }
    }

    // Handle disconnection
    ws.on('close', () => {
        if (type === 'app') {
            const tunnel = tunnels.get(token);
            if (tunnel) {
                tunnels.delete(token);
                payloads.delete(token);
                console.log(`❌ Tunnel closed: ${token}`);
            }
        } else if (type === 'payload') {
            for (const [key, value] of payloads.entries()) {
                if (value.ws === ws) {
                    payloads.delete(key);
                    const tunnel = tunnels.get(key);
                    if (tunnel) {
                        tunnel.ws.send(JSON.stringify({
                            type: 'payload_disconnected',
                            message: 'Payload has disconnected'
                        }));
                    }
                    console.log(`❌ Payload disconnected from tunnel: ${key}`);
                    break;
                }
            }
        }
    });
});

// Generate random token
function generateToken() {
    return Math.random().toString(36).substring(2, 15);
}

// Start server
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`🚀 Railway Tunnel Server running on port ${PORT}`);
    console.log(`📡 WebSocket endpoint: ws://localhost:${PORT}`);
    console.log(`🌐 HTTP endpoint: http://localhost:${PORT}`);
});
