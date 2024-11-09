// Express
import express from 'express';
// Env
import { config } from 'dotenv';
// Mongo
import { connectToMongo, connectToRedis } from './services/database.service';
// Signing service
import { signAndBroadcastBundlePayment, getBundledMessages } from './services/dao.service';
// Cors
import cors from 'cors';
// Controllers
import connectionsRouter from './routes/connections.route';
import transactionsRouter from './routes/transactions.route';
import daoRouter from './routes/dao.route';
import { getCosmWasmClient } from './services/wasmclient.service';

// Initializes env variables
config();

// Variables
const { API_PORT, DB_CONN_STRING, DB_NAME, REDIS_CONN_STRING, DAO_EXP_MODULE_ONLY } = process.env;

// API initialization
const app = express();

// Middlewares
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Cache
connectToRedis(REDIS_CONN_STRING);

//  routers
var ROUTER_CACHE = {};

// Setup DAO/EXP module only for price oracle
if(DAO_EXP_MODULE_ONLY && DAO_EXP_MODULE_ONLY.toLowerCase().startsWith('t')) {
    console.log(`DAO_EXP_MODULE_ONLY is ${DAO_EXP_MODULE_ONLY}`);
    app.use('/v1/dao', daoRouter)
} else {

    // if DB_CONN_STRING is undefined or empty, return error
    if (!DB_CONN_STRING || !DB_NAME) {
        console.error('DB_CONN_STRING or DB_NAME is not defined');
        process.exit(1);
    }

    // we only need MOngoDB if we use all the routes, excluding DAO/EXP
    connectToMongo(DB_CONN_STRING, DB_NAME);
    // Setup all routers (including the DAO)
    app.use('/v1/connections', connectionsRouter);
    app.use('/v1/tx', transactionsRouter);
    app.use('/v1/dao', daoRouter)
}



// Sends all our API endpoints
app.get('/', (req, res) => {
    if(Object.keys(ROUTER_CACHE).length === 0) {

        // there has to be a better way to do this..
        const urlStart = `${req.protocol}://${req.get('host')}`
        if(DAO_EXP_MODULE_ONLY && DAO_EXP_MODULE_ONLY.toLowerCase().startsWith('t')) {
            const daoRoutes = daoRouter.stack.map(({ route }) => `${urlStart}/v1/dao` + route.path)
            ROUTER_CACHE = { dao: daoRoutes }
        } else {
            // Setup all endpoints, including the DAO endpoint
            const connectionsRoutes = connectionsRouter.stack.map(({ route }) => `${urlStart}/v1/connections` + route.path)
            const transactionsRoutes = transactionsRouter.stack.map(({ route }) => `${urlStart}/v1/tx` + route.path)
            const daoRoutes = daoRouter.stack.map(({ route }) => `${urlStart}/v1/dao` + route.path)

            ROUTER_CACHE = {
                connections: connectionsRoutes,
                transactions: transactionsRoutes,
                dao: daoRoutes,
            }
        }
    }

    // send all routes
    res.json(ROUTER_CACHE)
});

// Start REST api
app.listen(API_PORT, async () => {
    console.log(`Started Integration REST API on port ${API_PORT}`);

    let intervalSeconds = 1000 * 20;

    // starting auto signing API process
    setInterval(async () => {
        let msgLen = getBundledMessages().length;
        // call signAndBroadcastBundlePayment
        if(msgLen > 0) {
            console.log("DEBUG: signAndBroadcastBundlePayment called with " + msgLen + " messages");
            const response = await signAndBroadcastBundlePayment(process.env.CRAFT_DAO_ESCROW_SECRET || "");
            if (response) {
                console.log("SUCCESS RESP: signAndBroadcastBundlePayment response: ", response);
            } else {
                console.log("ERROR RESP: signAndBroadcastBundlePayment response: ", response);
            }
        }
    }, intervalSeconds);

    const client = await getCosmWasmClient();
    if(client) {
        console.log(`Connected to Craftd node: ${process.env.CRAFTD_NODE}`);
    } else {
        console.log(`Error connecting to Craftd node: ${process.env.CRAFTD_NODE}`);
        process.exit(1);
    }
});
