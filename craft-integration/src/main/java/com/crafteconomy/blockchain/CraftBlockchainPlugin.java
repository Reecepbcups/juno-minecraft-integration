package com.crafteconomy.blockchain;

import com.crafteconomy.blockchain.api.IntegrationAPI;
import com.crafteconomy.blockchain.commands.escrow.EscrowCMD;
import com.crafteconomy.blockchain.commands.escrow.subcommands.EscrowBalance;
import com.crafteconomy.blockchain.commands.escrow.subcommands.EscrowDeposit;
import com.crafteconomy.blockchain.commands.escrow.subcommands.EscrowHelp;
import com.crafteconomy.blockchain.commands.escrow.subcommands.EscrowPay;
import com.crafteconomy.blockchain.commands.escrow.subcommands.EscrowRedeem;
import com.crafteconomy.blockchain.commands.wallet.WalletCMD;
import com.crafteconomy.blockchain.commands.wallet.subcommands.IBCTransfer;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletBalance;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletClearPending;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletFaucet;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletHelp;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletMyPendingTxs;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletOutputPendingTxs;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletSend;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletSet;
import com.crafteconomy.blockchain.commands.wallet.subcommands.WalletWebapp;
import com.crafteconomy.blockchain.commands.wallet.subcommands.debugging.WalletFakeSign;
import com.crafteconomy.blockchain.commands.wallet.subcommands.debugging.WalletGenerateFakeTx;
import com.crafteconomy.blockchain.commands.wallet.subcommands.debugging.WalletMultipleTxTesting;
import com.crafteconomy.blockchain.listeners.JoinLeave;
import com.crafteconomy.blockchain.storage.MongoDB;
import com.crafteconomy.blockchain.storage.RedisManager;
import com.crafteconomy.blockchain.transactions.PendingTransactions;
import com.crafteconomy.blockchain.transactions.listeners.ExpiredTransactionListener;
import com.crafteconomy.blockchain.transactions.listeners.RedisKeyListener;
import com.crafteconomy.blockchain.transactions.listeners.SignedTxCheckListener;
import com.crafteconomy.blockchain.utils.Util;
import com.crafteconomy.blockchain.wallets.WalletManager;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import redis.clients.jedis.Jedis;

// ********* IMPORTANT *********
// Ensure redis-cli -> `CONFIG SET notify-keyspace-events K$` (KEA also works)
// notify-keyspace-events = "KEA" in /etc/redis/redis.conf

public class CraftBlockchainPlugin extends JavaPlugin {
    private static CraftBlockchainPlugin instance;

    private static RedisManager redisDB;

    private static MongoDB mongoDB;

    public static String ADMIN_PERM = "crafteconomy.admin";

    private Double TAX_RATE;

    // TODO: Change this to be in the API itself.
    private String DAO_SERVER_WALLET = null;
    private String REST_API_WALLET_ADDRESS = null;

    private static String WALLET_PREFIX = "cosmos";
    private static String TOKEN_DENOM = "token";
    private static String TOKEN_DENOM_NAME = "Token";

    private String INTERNAL_API = null;
    private String API_MAKE_PAYMENT_ENDPOINT = INTERNAL_API + "/v1/dao/make_payment";
    private String API_IBC_TRANSFER_ITEM = INTERNAL_API + "/v1/dao/ibc_transfer";

    private BukkitTask redisPubSubTask = null;
    private Jedis jedisPubSubClient = null;
    private RedisKeyListener keyListener = null;

    private static String webappLink = null;
    private static String TX_QUERY_ENDPOINT = null;

    public static boolean ENABLED_FAUCET = false;

    private static Integer REDIS_MINUTE_TTL = 30;
    private static Boolean DEV_MODE = false;
    private static Boolean DEBUGGING_MSGS = false;

    public static String SERVER_NAME = "";

    @Override
    public void onEnable() {
        instance = this;

        // get SERVER_NAME
        String[] folder_location = Bukkit.getWorldContainer().getAbsolutePath().split("/");
        SERVER_NAME = folder_location[folder_location.length - 2]; // gets the world folder, then back a directory = server name (ex: economy-1)

        getConfig().options().copyDefaults(true);
        saveConfig();

        redisDB = new RedisManager(getConfig().getString("Redis.uri"));
        mongoDB = new MongoDB(getConfig().getString("MongoDB.uri"), getConfig().getString("MongoDB.database"));
        // redisDB = new RedisManager("redis://:PASSWORD@IP:6379");
        // mongoDB = new MongoDB("mongodb://USER:PASS@IP:PORT/?authSource=AUTHDB", "crafteconomy");

        log(redisDB.getRedisConnection().ping());
        log("" + mongoDB.getDatabase().getCollection("connections").countDocuments());

        INTERNAL_API = getConfig().getString("INTERNAL_API");
        if(INTERNAL_API.endsWith("/")) {
            INTERNAL_API = INTERNAL_API.substring(0, INTERNAL_API.length() - 1);
        }
        // ensure it has http at the start
        if(!INTERNAL_API.startsWith("http")) {
            log("INTERNAL_API does not start with http, adding it now. May fail who knows. Update this", Level.SEVERE);
            INTERNAL_API = "http://" + INTERNAL_API;
        }

        WALLET_PREFIX = getConfig().getString("WALLET_PREFIX");
        TOKEN_DENOM = getConfig().getString("TOKEN_DENOM");
        TOKEN_DENOM_NAME = getConfig().getString("TOKEN_DENOM_NAME");
        ADMIN_PERM = getConfig().getString("ADMIN_PERM");

        DAO_SERVER_WALLET = getConfig().getString("DAO_TAX_WALLET_ADDRESS"); // DAO_TAX_WALLET_ADDRESS
        REST_API_WALLET_ADDRESS = getConfig().getString("REST_API_WALLET_ADDRESS");
        API_MAKE_PAYMENT_ENDPOINT = getConfig().getString("API_MAKE_PAYMENT_ENDPOINT");

        webappLink = getConfig().getString("SIGNING_WEBAPP_LINK");
        TX_QUERY_ENDPOINT = getConfig().getString("TX_QUERY_ENDPOINT");

        TAX_RATE = getConfig().getDouble("TAX_RATE");
        if(TAX_RATE == null) TAX_RATE = 0.0;

        REDIS_MINUTE_TTL = getConfig().getInt("REDIS_MINUTE_TTL");
        if(REDIS_MINUTE_TTL == null) REDIS_MINUTE_TTL = 30;

        DEV_MODE = getConfig().getBoolean("DEV_MODE");
        if(DEV_MODE == null) DEV_MODE = false;

        DEBUGGING_MSGS = getConfig().getBoolean("DEBUGGING_MSGS");
        if(DEBUGGING_MSGS == null) DEBUGGING_MSGS = false;

        // if the server crashed before cleaing all keys, we clear them here
        RedisManager.getInstance().removePendingTxsOnStartupIfServerNameMatches(SERVER_NAME);

        if(DEV_MODE) {
            // async runnable every 4 minutes
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
                @Override
                public void run() {
                    Util.coloredBroadcast("&c&l[!] REMINDER, INTEGRATION DEV MODE ENABLED");
                }
            }, 0, 20*60*4);
        }


        if(getApiEndpoint() == null) {
            getLogger().severe("API REST (lcd) endpoint not set in config.yml, disabling plugin");
            getPluginLoader().disablePlugin(this);
            return;
        }

        WalletCMD cmd = new WalletCMD();
        getCommand("wallet").setExecutor(cmd);
        getCommand("wallet").setTabCompleter(cmd);

        cmd.registerCommand("help", new WalletHelp());
        cmd.registerCommand(new String[] {"b", "bal", "balance"}, new WalletBalance());
        cmd.registerCommand(new String[] {"set", "setwallet"}, new WalletSet());
        cmd.registerCommand(new String[] {"faucet", "deposit"}, new WalletFaucet());
        cmd.registerCommand(new String[] {"pay", "send"}, new WalletSend());
        cmd.registerCommand(new String[] {"webapp"}, new WalletWebapp());
        cmd.registerCommand(new String[] {"ibc-transfer"}, new IBCTransfer());

        // debug commands
        cmd.registerCommand(new String[] {"faketx"}, new WalletGenerateFakeTx());
        cmd.registerCommand(new String[] {"genfaketxstest"}, new WalletMultipleTxTesting());

        cmd.registerCommand(new String[] {"fakesign"}, new WalletFakeSign());
        cmd.registerCommand(new String[] {"allpending", "allkeys"}, new WalletOutputPendingTxs());
        cmd.registerCommand(new String[] {"mypending", "pending", "mykeys", "keys"}, new WalletMyPendingTxs());
        cmd.registerCommand(new String[] {"clearpending", "clear"}, new WalletClearPending());

        // arg[0] commands which will tab complete
        cmd.addTabComplete(new String[] {"balance","setwallet","send","pending","webapp"});

        // Escrow Commands
        EscrowCMD escrowCMD = new EscrowCMD();
        getCommand("escrow").setExecutor(escrowCMD);
        getCommand("escrow").setTabCompleter(escrowCMD);
        // register sub commands
        escrowCMD.registerCommand("help", new EscrowHelp());
        escrowCMD.registerCommand(new String[] {"b", "bal", "balance"}, new EscrowBalance());
        escrowCMD.registerCommand(new String[] {"d", "dep", "deposit"}, new EscrowDeposit());
        escrowCMD.registerCommand(new String[] {"r", "red", "redeem", "withdraw", "w"}, new EscrowRedeem());
        escrowCMD.registerCommand(new String[] {"p", "pay", "payment"}, new EscrowPay());
        // arg[0] commands which will tab complete
        escrowCMD.addTabComplete(new String[] {"balance","deposit","redeem","pay"});


        getServer().getPluginManager().registerEvents(new JoinLeave(), this);
        getServer().getPluginManager().registerEvents(new SignedTxCheckListener(), this);
        getServer().getPluginManager().registerEvents(new ExpiredTransactionListener(), this);


        // We dont want to crash main server thread. Running sync crashes main server thread
        keyListener = new RedisKeyListener();
        jedisPubSubClient = redisDB.getRedisConnection();
        redisPubSubTask = Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                CraftBlockchainPlugin.log("Starting Redis PubSub Client");
                // Webapp sends this request after the Tx has been signed
                // jedisPubSubClient.psubscribe(keyListener, "__key*__:signed_*");
                // jedisPubSubClient.psubscribe(keyListener, "__keyevent@*__:expire*"); // gets expired keys from redis (after Tx is removed), so we can remove from pending

                jedisPubSubClient.psubscribe(keyListener, "*");  // testing, but this works for now until I add a better regex pattern.
                // for now manual expires don't work, we just only show some patterns
            }
        });

        // set players wallets back to memory from database
        Bukkit.getOnlinePlayers().forEach(player -> WalletManager.getInstance().cacheWalletOnJoin(player.getUniqueId()));
    }

    @Override
    public void onDisable() {
        // TODO: some reason, this still crashes main server thread sometimes locally
        keyListener.unsubscribe();
        redisPubSubTask.cancel();

        // TODO This breaks getting resources from the redis pool on reload
        // Bukkit.getScheduler().cancelTasks(this);

        PendingTransactions.clearUncompletedTransactionsFromRedis();
        redisDB.closePool();
        mongoDB.disconnect();
        // jedisPubSubClient.close();

        Bukkit.getScheduler().cancelTasks(this);
    }

    public static int getRedisMinuteTTL() {
        return REDIS_MINUTE_TTL;
    }
    public static boolean getIfInDevMode() {
        return DEV_MODE;
    }

    public static String getTxQueryEndpoint() {
        // https://api.cosmos.network/cosmos/tx/v1beta1/txs/{TENDERMINT_HASH}
        // http://65.108.125.182:1317/cosmos/tx/v1beta1/txs/{TENDERMINT_HASH}
        return TX_QUERY_ENDPOINT;
    }

    public RedisManager getRedis() {
        return redisDB;
    }

    public MongoDB getMongo() {
        return mongoDB;
    }

    public static CraftBlockchainPlugin getInstance() {
        return instance;
    }

    public static IntegrationAPI getAPI() {
        return IntegrationAPI.getInstance();
    }

    public String getSecret() {
        return getConfig().getString("DAO_ESCROW_ENDPOINT_SECRET"); // random string of secret characters for rest api
    }

    public String getApiEndpoint() {
        // BlockchainAPI - :1317
        return getConfig().getString("API_ENDPOINT");
    }

    public String getWalletPrefix() {
        return WALLET_PREFIX;
    }
    public int getWalletLength() {
        return 39 + getWalletPrefix().length();
    }

    public String getTokenDenom() {
        return TOKEN_DENOM;
    }
    public String getTokenDenomName() {
        return TOKEN_DENOM_NAME;
    }

    public String getWebappLink() {
        return webappLink;
    }

    public Double getTaxRate() {
        return TAX_RATE;
    }

    public String getServerDaoTaxWallet() { // used for SERVER payments -> the DAO (taxes)
        return DAO_SERVER_WALLET;
    }

    public String getServerEscrowRestApiWalletAddress() { // used for ESCROW account payments
        return REST_API_WALLET_ADDRESS;
    }

    public String getApiMakePaymentEndpoint() {
        return API_MAKE_PAYMENT_ENDPOINT;
    }

    public String getApiIBCTransferItem() {
        return API_IBC_TRANSFER_ITEM;
    }

    public static void log(String msg, Level level) {
        // we allow only severe messages through, else its based on if debugging is enabled.
        if(level == Level.SEVERE) {
            Bukkit.getLogger().severe(msg);

        } else if(DEBUGGING_MSGS) {
            // Bukkit.getLogger().info(msg);
            Bukkit.getLogger().log(level, msg);
        }
    }
    public static void log(String msg) {
        log(msg, Level.INFO);
    }
}
