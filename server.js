const express = require('express');
const WebSocket = require('ws');
const http = require('http');
const net = require('net');
const cors = require('cors');
const admin = require('firebase-admin');

// ==================== FIREBASE INITIALIZATION ====================
let db = null;
let firebaseAvailable = false;

try {
    // Check if environment variable exists
    if (!process.env.FIREBASE_SERVICE_ACCOUNT) {
        console.error('❌ FIREBASE_SERVICE_ACCOUNT environment variable not set!');
        console.log('   Please add it in Railway Dashboard → Variables tab');
        console.log('   Running without Firebase validation...');
    } else {
        // Load Firebase credentials from environment variable
        const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
        
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
        
        db = admin.firestore();
        firebaseAvailable = true;
        console.log('✅ Firebase Firestore connected successfully');
        console.log(`   Project: ${serviceAccount.project_id}`);
    }
} catch (error) {
    console.error('❌ Failed to initialize Firebase:', error.message);
    console.log('   Running without Firebase validation...');
}

const app = express();

// ==================== CONFIGURATION ====================
// Master API key for server-to-server communication (keep this)
const MASTER_API_KEY = process.env.MASTER_API_KEY || 'fly0rakoon-bridge-key-2024';

// Fallback hardcoded API keys for legacy mode (when Firebase is not available)
const LEGACY_API_KEYS = new Set([
    process.env.API_KEY || 'fly0rakoon-secret-key-2024',
]);

// Store active data
const tunnels = new Map();
const payloads = new Map();
const pendingTunnels = new Map();
const tcpClients = new Map();
const gcpBridges = new Map();

// Helper function
function generateToken() {
    return Math.random().toString(36).substring(2, 15);
}

// ==================== AUTHENTICATION MIDDLEWARE ====================
const checkPassword = async (req, res, next) => {
    const apiKey = req.headers['x-api-key'];
    
    if (!apiKey) {
        return res.status(401).json({ 
            error: 'Unauthorized', 
            message: 'API key is required' 
        });
    }
    
    // Check if this is the master bridge key
    if (apiKey === MASTER_API_KEY) {
        return next();
    }
    
    // If Firebase is available, validate against Firestore
    if (firebaseAvailable && db) {
        try {
            // Query Firestore for this subscription key
            const tunnelsSnapshot = await db.collectionGroup('tunnels')
                .where('apiKey', '==', apiKey)
                .where('enabled', '==', true)
                .get();
            
            if (!tunnelsSnapshot.empty) {
                const tunnelData = tunnelsSnapshot.docs[0].data();
                const expiresAt = tunnelData.expiresAt?.toDate();
                
                // Check if not expired
                if (!expiresAt || expiresAt > new Date()) {
                    req.subscription = {
                        tunnelType: tunnelsSnapshot.docs[0].ref.path.split('/')[1].split('.')[1],
                        capabilities: tunnelData.capabilities || [],
                        userId: tunnelsSnapshot.docs[0].ref.path.split('/')[1],
                        expiresAt: expiresAt
                    };
                    return next();
                }
            }
            
            // Check legacy subscription field
            const userSnapshot = await db.collection('users')
                .where('subscription.apiKey', '==', apiKey)
                .where('subscription.status', '==', 'active')
                .get();
            
            if (!userSnapshot.empty) {
                const userData = userSnapshot.docs[0].data();
                const expiresAt = userData.subscription?.expiresAt?.toDate();
                
                if (!expiresAt || expiresAt > new Date()) {
                    req.subscription = {
                        tunnelType: userData.subscription?.tunnelType,
                        capabilities: userData.subscription?.purchasedCapabilities || [],
                        userId: userSnapshot.docs[0].id,
                        expiresAt: expiresAt
                    };
                    return next();
                }
            }
        } catch (error) {
            console.error('Firestore validation error:', error);
            // Fall through to legacy validation
        }
    }
    
    // Fallback to legacy hardcoded API keys
    if (LEGACY_API_KEYS.has(apiKey)) {
        console.log(`⚠️ Using legacy API key validation for: ${apiKey.substring(0, 10)}...`);
        return next();
    }
    
    return res.status(401).json({ 
        error: 'Unauthorized', 
        message: 'Invalid or expired subscription key' 
    });
};

app.use(cors());
app.use(express.json());
app.use('/api', checkPassword);

// Request logger
app.use((req, res, next) => {
    const subInfo = req.subscription ? ` (${req.subscription.tunnelType})` : '';
    console.log(`[${new Date().toISOString()}] HTTP: ${req.method} ${req.url}${subInfo}`);
    next();
});

// ==================== BASIC ENDPOINTS ====================
app.get('/', (req, res) => {
    res.json({
        status: 'online',
        version: '1.0.0',
        firebaseEnabled: firebaseAvailable,
        tunnels: tunnels.size,
        payloads: payloads.size,
        tcpClients: tcpClients.size,
        pending: pendingTunnels.size,
        gcpBridges: gcpBridges.size,
        timestamp: new Date().toISOString()
    });
});

app.get('/api/health', (req, res) => {
    res.json({
        status: 'healthy',
        firebaseEnabled: firebaseAvailable,
        timestamp: new Date().toISOString(),
        tunnels: tunnels.size,
        pending: pendingTunnels.size,
        tcpClients: tcpClients.size,
        gcpBridges: gcpBridges.size,
        uptime: process.uptime()
    });
});

// ==================== TUNNEL REGISTRATION ====================
app.post('/api/tunnel/register', async (req, res) => {
    const { port, type, subscriptionApiKey, userId, capabilities } = req.body;
    
    // If subscriptionApiKey provided, verify it exists in Firestore
    if (subscriptionApiKey && firebaseAvailable && db) {
        try {
            const tunnelsSnapshot = await db.collectionGroup('tunnels')
                .where('apiKey', '==', subscriptionApiKey)
                .get();
            
            if (tunnelsSnapshot.empty) {
                return res.status(401).json({ 
                    error: 'Invalid subscription', 
                    message: 'Subscription key not found' 
                });
            }
        } catch (error) {
            console.error('Error verifying subscription:', error);
        }
    }
    
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

// ==================== GCP TUNNEL MANAGEMENT ====================
app.post('/api/tunnel/start', (req, res) => {
    const { tunnelId, service, port, apiKey } = req.body;
    
    console.log(`[${new Date().toISOString()}] 🚀 Starting tunnel: ${tunnelId}, service=${service}, port=${port}`);
    
    if (gcpBridges.size === 0) {
        return res.status(503).json({ 
            error: 'No GCP VM available. Please try again later.' 
        });
    }
    
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

app.post('/api/tunnel/stop', (req, res) => {
    const { tunnelId } = req.body;
    
    console.log(`[${new Date().toISOString()}] 🛑 Stopping tunnel: ${tunnelId}`);
    
    let stopped = false;
    for (const [bridgeId, bridge] of gcpBridges) {
        bridge.ws.send(JSON.stringify({
            type: 'stop_tunnel',
            tunnelId: tunnelId
        }));
        stopped = true;
    }
    
    res.json({
        success: true,
        message: stopped ? 'Stop command sent' : 'No bridges available',
        tunnelId: tunnelId
    });
});

// ==================== API KEY VALIDATION ENDPOINTS ====================

// Validate a tunnel API key (called by Flutter app before building payload)
app.post('/api/validate-key', async (req, res) => {
    const { apiKey, tunnelType } = req.body;
    
    console.log(`[${new Date().toISOString()}] 🔐 Validating API key for ${tunnelType} tunnel`);
    
    // If Firebase is not available, use legacy validation
    if (!firebaseAvailable || !db) {
        console.log(`⚠️ Firebase not available, using legacy validation`);
        // Accept any non-empty key in legacy mode
        if (apiKey && apiKey.length > 5) {
            return res.json({
                valid: true,
                tunnelType: tunnelType,
                message: 'Legacy mode: Key accepted'
            });
        } else {
            return res.status(401).json({
                valid: false,
                message: 'Invalid API key'
            });
        }
    }
    
    try {
        // Query Firestore for this subscription key
        const tunnelsSnapshot = await db.collectionGroup('tunnels')
            .where('apiKey', '==', apiKey)
            .where('enabled', '==', true)
            .get();
        
        if (!tunnelsSnapshot.empty) {
            const tunnelData = tunnelsSnapshot.docs[0].data();
            const expiresAt = tunnelData.expiresAt?.toDate();
            
            // Check if not expired
            if (!expiresAt || expiresAt > new Date()) {
                console.log(`✅ Valid subscription key for ${tunnelType}`);
                return res.json({
                    valid: true,
                    expiresAt: expiresAt ? expiresAt.toISOString() : null,
                    tunnelType: tunnelType,
                    capabilities: tunnelData.capabilities || [],
                    message: 'Subscription is valid'
                });
            } else {
                console.log(`❌ Subscription expired for ${tunnelType}`);
                return res.status(401).json({
                    valid: false,
                    message: 'Subscription has expired',
                    expiresAt: expiresAt.toISOString()
                });
            }
        }
        
        // Check legacy subscription field
        const userSnapshot = await db.collection('users')
            .where('subscription.apiKey', '==', apiKey)
            .where('subscription.status', '==', 'active')
            .get();
        
        if (!userSnapshot.empty) {
            const userData = userSnapshot.docs[0].data();
            const expiresAt = userData.subscription?.expiresAt?.toDate();
            
            if (!expiresAt || expiresAt > new Date()) {
                console.log(`✅ Valid legacy subscription for ${tunnelType}`);
                return res.json({
                    valid: true,
                    expiresAt: expiresAt ? expiresAt.toISOString() : null,
                    tunnelType: tunnelType,
                    capabilities: userData.subscription?.purchasedCapabilities || [],
                    message: 'Subscription is valid'
                });
            }
        }
        
        console.log(`❌ Invalid subscription key for ${tunnelType}`);
        return res.status(401).json({
            valid: false,
            message: 'Invalid or expired subscription key'
        });
        
    } catch (error) {
        console.error('Validation error:', error);
        return res.status(500).json({
            valid: false,
            message: 'Internal server error during validation'
        });
    }
});

// Get user's API keys for all tunnels (for Settings screen)
app.get('/api/user/keys', async (req, res) => {
    console.log(`[${new Date().toISOString()}] 🔑 Fetching user API keys`);
    
    res.json({
        railway: { apiKey: null, enabled: false, message: 'Configure in app' },
        kami: { apiKey: null, enabled: false, message: 'Configure in app' },
        gotunnel: { apiKey: null, enabled: false, message: 'Configure in app' },
        custom: { apiKey: null, enabled: true, message: 'No key required' }
    });
});

// Get specific tunnel API key
app.get('/api/tunnel-key', async (req, res) => {
    const { type } = req.query;
    
    console.log(`[${new Date().toISOString()}] 🔑 Fetching API key for ${type}`);
    
    res.json({
        apiKey: null,
        tunnelType: type,
        expiresAt: null,
        message: 'Configure in app settings'
    });
});

// Check if user has access to a specific tunnel type based on subscription
app.get('/api/has-access', async (req, res) => {
    const { type } = req.query;
    const apiKey = req.headers['x-api-key'];
    
    console.log(`[${new Date().toISOString()}] 🔍 Checking access for ${type}`);
    
    if (!apiKey) {
        return res.json({
            hasAccess: false,
            tunnelType: type,
            message: 'No API key provided'
        });
    }
    
    // If Firebase is not available, grant access in legacy mode
    if (!firebaseAvailable || !db) {
        console.log(`⚠️ Firebase not available, granting access in legacy mode`);
        return res.json({
            hasAccess: true,
            tunnelType: type,
            message: 'Legacy mode: Access granted'
        });
    }
    
    try {
        // Check if user has a subscription for this tunnel type
        const tunnelsSnapshot = await db.collectionGroup('tunnels')
            .where('apiKey', '==', apiKey)
            .where('enabled', '==', true)
            .get();
        
        if (!tunnelsSnapshot.empty) {
            const tunnelData = tunnelsSnapshot.docs[0].data();
            const tunnelKey = tunnelsSnapshot.docs[0].ref.path.split('/')[1].split('.')[1];
            const expiresAt = tunnelData.expiresAt?.toDate();
            
            if (tunnelKey === type && (!expiresAt || expiresAt > new Date())) {
                return res.json({
                    hasAccess: true,
                    tunnelType: type,
                    message: 'Access granted',
                    expiresAt: expiresAt ? expiresAt.toISOString() : null,
                    remainingBuilds: (tunnelData.maxBuilds || 10) - (tunnelData.buildsUsed || 0)
                });
            }
        }
        
        return res.json({
            hasAccess: false,
            tunnelType: type,
            message: `No active subscription for ${type} tunnel`
        });
        
    } catch (error) {
        console.error('Access check error:', error);
        return res.json({
            hasAccess: false,
            tunnelType: type,
            message: 'Error checking access'
        });
    }
});

// ==================== CATCH-ALL ROUTE ====================
app.use((req, res) => {
    res.status(404).json({
        status: 'error',
        message: `Route ${req.method} ${req.url} not found`,
        availableRoutes: [
            'GET /',
            'GET /api/health',
            'POST /api/tunnel/register',
            'GET /api/tunnel/:id',
            'POST /api/tunnel/start',
            'POST /api/tunnel/stop',
            'POST /api/validate-key',
            'GET /api/user/keys',
            'GET /api/tunnel-key',
            'GET /api/has-access',
            'WebSocket /bridge'
        ]
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
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;
    
    // GCP BRIDGE HANDLER
    if (pathname === '/bridge') {
        const apiKey = url.searchParams.get('key');
        
        if (apiKey !== MASTER_API_KEY) {
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
    
    // FLUTTER APP & PAYLOAD WEBSOCKET HANDLER
    const type = url.searchParams.get('type');
    const port = url.searchParams.get('port');
    const token = url.searchParams.get('token') || generateToken();

    console.log(`[${new Date().toISOString()}] WebSocket connection: ${type}, port: ${port}, token: ${token}`);

    if (type === 'app') {
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

// ==================== START SERVER ====================
const PORT = process.env.PORT || 8080;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n${'='.repeat(50)}`);
    console.log(`🚀 Fly0Rakoon Tunnel Server`);
    console.log(`${'='.repeat(50)}`);
    console.log(`📡 HTTP/WebSocket Server: http://localhost:${PORT}`);
    console.log(`🔌 TCP Endpoint: tcp://localhost:${TCP_PORT}`);
    console.log(`🔐 Master API Key: ${MASTER_API_KEY}`);
    console.log(`🔥 Firebase Status: ${firebaseAvailable ? 'CONNECTED ✅' : 'DISABLED ⚠️'}`);
    if (!firebaseAvailable) {
        console.log(`\n⚠️  WARNING: Firebase is not configured!`);
        console.log(`   Add FIREBASE_SERVICE_ACCOUNT environment variable to enable validation.`);
        console.log(`   Currently running in legacy mode with hardcoded API keys.`);
    }
    console.log(`${'='.repeat(50)}\n`);
});
