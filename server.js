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

// ==================== MIDDLEWARE ====================
app.use(cors());
app.use(express.json());

// Request logger (will be used after auth for protected routes)
const requestLogger = (req, res, next) => {
    const subInfo = req.subscription ? ` (${req.subscription.tunnelType})` : '';
    console.log(`[${new Date().toISOString()}] HTTP: ${req.method} ${req.url}${subInfo}`);
    next();
};

// ==================== PUBLIC ENDPOINTS (NO API KEY REQUIRED) ====================

// Public health check
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

// Debug endpoint to test Firestore connection
app.get('/api/debug/firestore', async (req, res) => {
    try {
        if (!firebaseAvailable || !db) {
            return res.json({
                firebaseAvailable: false,
                message: 'Firebase not available'
            });
        }
        
        const usersSnapshot = await db.collection('users').limit(10).get();
        const users = [];
        
        usersSnapshot.forEach(doc => {
            const data = doc.data();
            users.push({
                id: doc.id,
                hasTunnels: !!data.tunnels,
                hasSubscription: !!data.subscription,
                tunnelKeys: data.tunnels ? Object.keys(data.tunnels) : [],
                subscriptionTunnelType: data.subscription?.tunnelType || null
            });
        });
        
        res.json({
            firebaseAvailable: true,
            userCount: usersSnapshot.size,
            users: users
        });
    } catch (error) {
        console.error('Debug error:', error);
        res.status(500).json({
            error: error.message,
            stack: error.stack
        });
    }
});

// Public validate-key endpoint (called by Flutter app before building payload)
app.post('/api/validate-key', async (req, res) => {
    const { apiKey, tunnelType } = req.body;
    
    console.log(`[${new Date().toISOString()}] 🔐 Validating API key for ${tunnelType} tunnel`);
    console.log(`   API Key provided: ${apiKey ? apiKey.substring(0, 20) + '...' : 'null'}`);
    
    if (!firebaseAvailable || !db) {
        console.log(`⚠️ Firebase not available, using legacy validation`);
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
        let foundSubscription = null;
        let foundTunnelType = null;
        let userId = null;
        
        console.log('   Checking legacy subscription field...');
        const userSnapshot = await db.collection('users')
            .where('subscription.apiKey', '==', apiKey)
            .where('subscription.status', '==', 'active')
            .get();
        
        if (!userSnapshot.empty) {
            const userDoc = userSnapshot.docs[0];
            const userData = userDoc.data();
            const subData = userData.subscription;
            const expiresAt = subData?.expiresAt?.toDate();
            
            if (!expiresAt || expiresAt > new Date()) {
                foundSubscription = subData;
                foundTunnelType = subData.tunnelType;
                userId = userDoc.id;
                console.log(`   ✅ Found legacy subscription for user ${userId}, tunnel: ${foundTunnelType}`);
            } else {
                console.log(`   ❌ Legacy subscription expired for user ${userDoc.id}`);
            }
        }
        
        if (!foundSubscription) {
            console.log('   Checking tunnels map...');
            try {
                const tunnelsSnapshot = await db.collectionGroup('tunnels')
                    .where('apiKey', '==', apiKey)
                    .where('enabled', '==', true)
                    .get();
                
                if (!tunnelsSnapshot.empty) {
                    const doc = tunnelsSnapshot.docs[0];
                    const tunnelData = doc.data();
                    const pathParts = doc.ref.path.split('/');
                    foundTunnelType = pathParts[pathParts.length - 1];
                    foundSubscription = tunnelData;
                    userId = pathParts[1];
                    console.log(`   ✅ Found in tunnels map for user ${userId}, tunnel: ${foundTunnelType}`);
                }
            } catch (collectionError) {
                console.log(`   Collection group query: ${collectionError.message}`);
            }
        }
        
        if (foundSubscription) {
            let expiresAt = foundSubscription.expiresAt;
            if (expiresAt && typeof expiresAt.toDate === 'function') {
                expiresAt = expiresAt.toDate();
            }
            
            console.log(`✅ Valid subscription key for ${foundTunnelType}`);
            return res.json({
                valid: true,
                expiresAt: expiresAt ? expiresAt.toISOString() : null,
                tunnelType: foundTunnelType,
                capabilities: foundSubscription.capabilities || foundSubscription.purchasedCapabilities || [],
                userId: userId,
                message: 'Subscription is valid'
            });
        }
        
        console.log(`❌ No subscription found for key: ${apiKey ? apiKey.substring(0, 20) + '...' : 'null'}`);
        return res.status(401).json({
            valid: false,
            message: 'Invalid or expired subscription key'
        });
        
    } catch (error) {
        console.error('❌ Validation error:', error);
        console.error('   Error stack:', error.stack);
        return res.status(500).json({
            valid: false,
            message: 'Internal server error during validation',
            error: error.message
        });
    }
});

// ✅ PUBLIC tunnel registration (NO API KEY REQUIRED)
app.post('/api/tunnel/register', async (req, res) => {
    const { port, type, subscriptionApiKey, userId, capabilities } = req.body;
    
    console.log(`[${new Date().toISOString()}] 📝 Tunnel registration: port=${port}, type=${type}`);
    
    // Validate subscription key if provided
    if (subscriptionApiKey && firebaseAvailable && db) {
        try {
            const userSnapshot = await db.collection('users')
                .where('subscription.apiKey', '==', subscriptionApiKey)
                .where('subscription.status', '==', 'active')
                .get();
            
            if (userSnapshot.empty) {
                console.log(`❌ Invalid subscription key: ${subscriptionApiKey.substring(0, 20)}...`);
                return res.status(401).json({ 
                    error: 'Invalid subscription', 
                    message: 'Subscription key not found or inactive' 
                });
            }
            console.log(`✅ Subscription validated for user: ${userSnapshot.docs[0].id}`);
        } catch (error) {
            console.error('Error verifying subscription:', error);
        }
    }
    
    const token = generateToken();
    
    console.log(`✅ Tunnel registered: token=${token}, port=${port}`);
    
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


// ✅ ADD THIS NEW HEALTH ENDPOINT RIGHT HERE
app.get('/health', (req, res) => {
    res.status(200).json({ 
        status: 'ok', 
        timestamp: new Date().toISOString() 
    });
});
// ==================== AUTHENTICATION MIDDLEWARE ====================
const checkPassword = async (req, res, next) => {
    const apiKey = req.headers['x-api-key'];
    
    if (!apiKey) {
        return res.status(401).json({ 
            error: 'Unauthorized', 
            message: 'API key is required' 
        });
    }
    
    if (apiKey === MASTER_API_KEY) {
        return next();
    }
    
    if (firebaseAvailable && db) {
        try {
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
            
            const tunnelsSnapshot = await db.collectionGroup('tunnels')
                .where('apiKey', '==', apiKey)
                .where('enabled', '==', true)
                .get();
            
            if (!tunnelsSnapshot.empty) {
                const tunnelData = tunnelsSnapshot.docs[0].data();
                const expiresAt = tunnelData.expiresAt?.toDate();
                
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
        } catch (error) {
            console.error('Firestore validation error:', error);
        }
    }
    
    if (LEGACY_API_KEYS.has(apiKey)) {
        console.log(`⚠️ Using legacy API key validation for: ${apiKey.substring(0, 10)}...`);
        return next();
    }
    
    return res.status(401).json({ 
        error: 'Unauthorized', 
        message: 'Invalid or expired subscription key' 
    });
};

// Apply authentication middleware to PROTECTED routes only
app.use('/api/tunnel/start', checkPassword);
app.use('/api/tunnel/stop', checkPassword);
app.use('/api/user', checkPassword);
app.use('/api/has-access', checkPassword);
app.use('/api/tunnel-key', checkPassword);

// Apply request logger to protected routes
app.use('/api/tunnel/start', requestLogger);
app.use('/api/tunnel/stop', requestLogger);
app.use('/api/user', requestLogger);

// ==================== BASIC ENDPOINTS (Public) ====================
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

// ==================== PROTECTED TUNNEL ENDPOINTS ====================

// Get tunnel info (requires auth)
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

// Start tunnel (requires auth)
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

// Stop tunnel (requires auth)
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

// ==================== PROTECTED USER ENDPOINTS ====================

// Get user's API keys (requires auth)
app.get('/api/user/keys', async (req, res) => {
    console.log(`[${new Date().toISOString()}] 🔑 Fetching user API keys`);
    
    res.json({
        railway: { apiKey: null, enabled: false, message: 'Configure in app' },
        kami: { apiKey: null, enabled: false, message: 'Configure in app' },
        gotunnel: { apiKey: null, enabled: false, message: 'Configure in app' },
        custom: { apiKey: null, enabled: true, message: 'No key required' }
    });
});

// Get specific tunnel API key (requires auth)
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

// Check if user has access (requires auth)
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
    
    if (!firebaseAvailable || !db) {
        console.log(`⚠️ Firebase not available, granting access in legacy mode`);
        return res.json({
            hasAccess: true,
            tunnelType: type,
            message: 'Legacy mode: Access granted'
        });
    }
    
    try {
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
            'GET /api/debug/firestore',
            'POST /api/validate-key',
            'POST /api/tunnel/register',
            'GET /api/tunnel/:id',
            'POST /api/tunnel/start',
            'POST /api/tunnel/stop',
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
    console.log(`\n📋 Public Endpoints (no auth required):`);
    console.log(`   GET  /api/health`);
    console.log(`   GET  /api/debug/firestore`);
    console.log(`   POST /api/validate-key`);
    console.log(`   POST /api/tunnel/register`);
    console.log(`\n🔒 Protected Endpoints (require x-api-key header):`);
    console.log(`   POST /api/tunnel/start`);
    console.log(`   POST /api/tunnel/stop`);
    console.log(`   GET  /api/has-access`);
    console.log(`   GET  /api/user/keys`);
    console.log(`   GET  /api/tunnel-key`);
    if (!firebaseAvailable) {
        console.log(`\n⚠️  WARNING: Firebase is not configured!`);
        console.log(`   Add FIREBASE_SERVICE_ACCOUNT environment variable to enable validation.`);
        console.log(`   Currently running in legacy mode with hardcoded API keys.`);
    }
    console.log(`${'='.repeat(50)}\n`);
});
