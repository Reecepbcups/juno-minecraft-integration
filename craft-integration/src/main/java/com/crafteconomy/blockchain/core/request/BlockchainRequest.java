package com.crafteconomy.blockchain.core.request;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.crafteconomy.blockchain.CraftBlockchainPlugin;
import com.crafteconomy.blockchain.core.types.CosmWasmTypes;
import com.crafteconomy.blockchain.core.types.ErrorTypes;
import com.crafteconomy.blockchain.core.types.FaucetTypes;
import com.crafteconomy.blockchain.core.types.RequestTypes;
import com.crafteconomy.blockchain.core.types.TransactionType;
import com.crafteconomy.blockchain.storage.RedisManager;
import com.crafteconomy.blockchain.transactions.PendingTransactions;
import com.crafteconomy.blockchain.transactions.Tx;
import com.crafteconomy.blockchain.utils.JavaUtils;
import com.crafteconomy.blockchain.utils.Util;
import com.crafteconomy.blockchain.wallets.WalletManager;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.JSONException;
import org.json.simple.JSONObject;

public class BlockchainRequest {

    private static CraftBlockchainPlugin blockchainPlugin = CraftBlockchainPlugin.getInstance();
    private static RedisManager redisDB = blockchainPlugin.getRedis();
    private static String DAO_TAX_WALLET = blockchainPlugin.getServerDaoTaxWallet(); // taxes paid directly to the DAO

    // http://65.108.125.182:1317/cosmos/bank/v1beta1
    private static final String API_ENDPOINT = blockchainPlugin.getApiEndpoint();

    private static DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private static WalletManager walletManager = WalletManager.getInstance();

    // Found via https://v1.cosmos.network/rpc/v0.41.4
    private static final String BALANCES_ENDPOINT = API_ENDPOINT + "cosmos/bank/v1beta1/balances/%address%/by_denom?denom=%denomination%";
    // private static final String SUPPLY_ENDPOINT = API_ENDPOINT + "cosmos/bank/v1beta1/supply/by_denom?denom=%denomination%";

    // -= BALANCES =-
    public static CompletableFuture<Long> getBalance(String craft_address, String denomination) {
        CompletableFuture<Long> future = CompletableFuture.supplyAsync(new Supplier<Long>() {
            @Override
            public Long get() {
                if(craft_address == null) {
                    return (long) ErrorTypes.NO_WALLET.code;
                }

                Object cacheAmount = Caches.getIfPresent(RequestTypes.BALANCE, craft_address);
                if(cacheAmount != null) {
                    return (long) cacheAmount;
                }

                // Add uexp as well?- http://65.109.38.251:1317/cosmos/bank/v1beta1/balances/craft10r39fueph9fq7a6lgswu4zdsg8t3gxlqd6lnf0

                String req_url = BALANCES_ENDPOINT.replace("%address%", craft_address).replace("%denomination%", denomination);

                System.out.println("bal req_url " +  req_url);

                long amount = Long.parseLong(EndpointQuery.req(req_url, RequestTypes.BALANCE, "Balance Request").toString());

                Caches.put(RequestTypes.BALANCE, craft_address, amount);
                return amount;
            }
        });
        return future;

    }

    public static CompletableFuture<Long> getUCraftBalance(String craft_address) { // 1_000_000utoken = 1token
        return getBalance(craft_address, blockchainPlugin.getTokenDenom());
    }

    public static CompletableFuture<Float> getCraftBalance(String craft_address) { // 1 token
        return getUCraftBalance(craft_address).thenApply(ucraft -> (float) (ucraft / 1_000_000));
    }


    // -= TOTAL SUPPLY =-
    // public static CompletableFuture<Long> getTotalSupply(String denomination) {
    //     CompletableFuture<Long> future = CompletableFuture.supplyAsync(new Supplier<Long>() {
    //         @Override
    //         public Long get() {
    //             Object totalSupply = Caches.getIfPresent(RequestTypes.SUPPLY, denomination);
    //             if(totalSupply != null) { return (long) totalSupply; }

    //             String URL = SUPPLY_ENDPOINT.replace("%denomination%", denomination);
    //             long supply = Long.parseLong(EndpointQuery.req(URL, RequestTypes.SUPPLY, "Total Supply Request").toString());

    //             Caches.put(RequestTypes.SUPPLY, denomination, supply);
    //             return supply;
    //         }
    //     });
    //     return future;
    // }

    // public static CompletableFuture<Long> getTotalSupply() {
    //     return getTotalSupply(blockchainPlugin.getTokenDenom());
    // }

    // -= GIVING TOKENS =-
    private static final String ENDPOINT_SECRET = CraftBlockchainPlugin.getInstance().getSecret();
    private static FaucetTypes makePostRequest(String craft_address, String description, long ucraft_amount) {
        if(craft_address == null) { return FaucetTypes.NO_WALLET; }

        URL url = null;
        HttpURLConnection http = null;
        OutputStream stream = null;
        String endpoint = CraftBlockchainPlugin.getInstance().getApiMakePaymentEndpoint();
        String data = "{\"secret\": \""+ENDPOINT_SECRET+"\", \"description\": \""+description+"\", \"wallet\": \""+craft_address+"\", \"ucraft_amount\": \""+ucraft_amount+"\"}";
        // CraftBlockchainPlugin.log("url: "+endpoint+", depositToAddress data " + data);

        try {
            url = new URL(endpoint);
            http = (HttpURLConnection)url.openConnection();
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");

            byte[] out = data.getBytes(StandardCharsets.UTF_8);
            stream = http.getOutputStream();
            stream.write(out);

            // get the return value of the POST request
            // {"success":{"craft_amount":"1","wallet":"craft10r39fueph9fq7a6lgswu4zdsg8t3gxlqd6lnf0","ucraft_amount":"1000000","serverCraftBalLeft":"999999910.196505craft",
            //      "transactionHash":"EFF47C0977F82CC6533B6CFDDF7E5D93A45D7F955210B457B3CD8DE6E33EA289","height":40486}}
            String response = JavaUtils.streamToString(http.getInputStream());
            if(response.length() == 0) {
                System.err.println("No response from server API (length 0 string)");
                return FaucetTypes.NO_RESPONSE;
            }

            JSONObject json = new JSONObject();
            json = (JSONObject) org.json.simple.JSONValue.parse(response);


            CraftBlockchainPlugin.log("API Response: " + http.getResponseCode() + " | response: " + json);
            http.disconnect();

            if(http.getResponseCode() != 200) {
                CraftBlockchainPlugin.log("Failed payment!");
                return FaucetTypes.FAILURE;
            }

            if(json.keySet().contains("success")) {
                CraftBlockchainPlugin.log("Successful payment!");
                return FaucetTypes.SUCCESS;

            } else if (json.keySet().contains("error")) {
                boolean doSaveToDBForRunLater = true;
                json = (JSONObject) json.get("error");
                String errorCode = (String) json.get("code");
                // https://github.com/cosmos/cosmos-sdk/blob/main/types/errors/errors.go

                String output = "";
                FaucetTypes returnType = FaucetTypes.FAILURE;
                switch (errorCode) {
                    case "5" -> {
                        output = "Server wallet does not have enough funds!";
                        returnType = FaucetTypes.NOT_ENOUGH_FUNDS_IN_SERVER_WALLET;
                    }
                    case "3" -> {
                        output = "Invalid sequence!";
                    }
                    case "19" -> {
                        output = "Transaction is already in the mem pool (duplicate transaction)!";
                        doSaveToDBForRunLater = false;
                    }
                    default -> {
                        output = "No success in response from server API: " + json;
                    }
                }
                CraftBlockchainPlugin.log(output, Level.SEVERE);
                if(doSaveToDBForRunLater) { saveFailedTransaction(craft_address, description, ucraft_amount, output); }

                return returnType;
            }

        } catch (Exception e) {
            e.printStackTrace();

            saveFailedTransaction(craft_address, description, ucraft_amount, e.getMessage());
            if(e.getMessage().startsWith("Server returned HTTP response code: 502 for URL:")) {
                CraftBlockchainPlugin.log("makePayment API is down!", Level.SEVERE);
                return FaucetTypes.API_DOWN;
            } else {
                CraftBlockchainPlugin.log("makePayment API is down!", Level.SEVERE);
                return FaucetTypes.FAILURE;
            }
        }

        return FaucetTypes.FAILURE;
    }

    // save a failed transaction to the database to be run later. From EscrowManager.java
    private static MongoDatabase db = CraftBlockchainPlugin.getInstance().getMongo().getDatabase();
    private static String FAILED_TXS = "failedTxs";
    public static void saveFailedTransaction(String craft_address, String description, long ucraft_amount, String failure_reason) {
        CraftBlockchainPlugin.log("Saving failed transaction to database...");

        // get current time in human readable format
        Document doc = getUsersDocument(craft_address);
        Document failedTX = createFailedTransaction(craft_address, description, ucraft_amount, failure_reason);

        if(doc == null) { // users first unpaied payment
            // put the document into database as an array
            ArrayList<Document> FaileTxsList = new ArrayList<Document>();
            FaileTxsList.add(failedTX);

            doc = new Document("craft_address", craft_address);
            doc.put(FAILED_TXS, FaileTxsList);

            getCollection().insertOne(doc);

        } else {
            // getCollection().updateOne(Filters.eq("_id", uuid.toString()), Updates.set("ucraft_amount", newBalance));
            Object s = doc.get(FAILED_TXS);
            ArrayList<Document> failedTXs = (ArrayList<Document>) s;
            if (failedTXs == null) {
                failedTXs = new ArrayList<Document>();
            }

            failedTXs.add(failedTX);
            doc.put(FAILED_TXS, failedTXs);
            getCollection().replaceOne(Filters.eq("craft_address", craft_address), doc);
        }

        // Notify the user that their payment failed but is pending for later send.
        Optional<UUID> uuid = walletManager.getUUIDFromWallet(craft_address);
        if(uuid.isPresent()) {
            // get the uuid & see if player is online
            UUID uuidValue = uuid.get();
            Player p = Bukkit.getPlayer(uuidValue);
            if(p != null) {
                Util.colorMsg(p, "&6[!] Error: Payment failed. Saved to database & will be tried again soon.");
                Util.colorMsg(p, "&e&oReason: " + failure_reason);
                Util.colorMsg(p, "&e&oAmount: " + ucraft_amount/1_000_000 + blockchainPlugin.getTokenDenomName() + ".");
                Util.colorMsg(p, "&6NOTE: No action is required on your part.");
            }
        }

        CraftBlockchainPlugin.log("Saved failed transaction to database!");
    }
    private static Document createFailedTransaction(String craft_address, String description, long ucraft_amount, String failure_reason) {
        Document doc = new Document();
        doc.put("craft_address", craft_address);
        doc.put("description", description);
        doc.put("ucraft_amount", ucraft_amount);
        doc.put("time_epoch", System.currentTimeMillis() / 1000);
        doc.put("time_human", dtf.format(LocalDateTime.now()));
        doc.put("failure_reason", failure_reason);
        return doc;
    }
    private static Document getUsersDocument(String craft_address) {
        Bson filter = Filters.eq("craft_address", craft_address);
        return getCollection().find(filter).first();
    }
    private static MongoCollection<Document> getCollection() {
        return db.getCollection("failedPayments");
    }


    public static CompletableFuture<FaucetTypes> depositUCraftToAddress(String craft_address, String description, long utoken_amount) {
        // curl --data '{"secret": "7821719493", "wallet": "craft10r39fueph9fq7a6lgswu4zdsg8t3gxlqd6lnf0", "amount": 50000}' -X POST -H "Content-Type: application/json"  http://api.crafteconomy.io/v1/dao/make_payment
        return CompletableFuture.supplyAsync(() -> makePostRequest(craft_address, description, utoken_amount)).completeOnTimeout(FaucetTypes.ENDPOINT_TIMEOUT, 45, TimeUnit.SECONDS);

    }
    public static CompletableFuture<FaucetTypes> depositCraftToAddress(String craft_address, String description, float craft) {
        return depositUCraftToAddress(craft_address, description, (long)(craft*1_000_000));
    }


    public static CompletableFuture<CosmWasmTypes> ibcTransferItemWithCosmWasm(String craft_address, String itemBase64) {
        return CompletableFuture.supplyAsync(() -> makeCosmWasmRequest(craft_address, itemBase64)).completeOnTimeout(CosmWasmTypes.ENDPOINT_TIMEOUT, 45, TimeUnit.SECONDS);
    }
    private static CosmWasmTypes makeCosmWasmRequest(String craft_address, String itemBase64) {
        // if(craft_address == null) { return CosmWasmTypes.NO_WALLET; }

        URL url = null;
        HttpURLConnection http = null;
        OutputStream stream = null;
        String endpoint = CraftBlockchainPlugin.getInstance().getApiIBCTransferItem();
        String data = "{\"secret\": \""+ENDPOINT_SECRET+"\", \"item\": \""+itemBase64+"\", \"wallet\": \""+craft_address+"\"}";
        CraftBlockchainPlugin.log("url: "+endpoint+", makeCosmWasmRequest data " + data);

        try {
            url = new URL(endpoint);
            http = (HttpURLConnection)url.openConnection();
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");

            byte[] out = data.getBytes(StandardCharsets.UTF_8);
            stream = http.getOutputStream();
            stream.write(out);

            // get the return value of the POST request
            // {"success":{"craft_amount":"1","wallet":"craft10r39fueph9fq7a6lgswu4zdsg8t3gxlqd6lnf0","ucraft_amount":"1000000","serverCraftBalLeft":"999999910.196505craft",
            //      "transactionHash":"EFF47C0977F82CC6533B6CFDDF7E5D93A45D7F955210B457B3CD8DE6E33EA289","height":40486}}
            String response = JavaUtils.streamToString(http.getInputStream());
            if(response.length() == 0) {
                System.err.println("No response from server API (length 0 string)");
                return CosmWasmTypes.FAILURE;
            }

            JSONObject json = new JSONObject();
            json = (JSONObject) org.json.simple.JSONValue.parse(response);


            CraftBlockchainPlugin.log("API Response: " + http.getResponseCode() + " | response: " + json);
            http.disconnect();

            if(http.getResponseCode() != 200) {
                CraftBlockchainPlugin.log("Failed payment!");
                return CosmWasmTypes.FAILURE;
            }

            if(json.keySet().contains("success")) {
                CraftBlockchainPlugin.log("Successful payment!");
                return CosmWasmTypes.SUCCESS;

            }

        } catch (Exception e) {
            e.printStackTrace();

            // saveFailedTransaction(craft_address, description, ucraft_amount, e.getMessage());
            if(e.getMessage().startsWith("Server returned HTTP response code: 502 for URL:")) {
                CraftBlockchainPlugin.log("makePayment API is down!", Level.SEVERE);
                return CosmWasmTypes.FAILURE;
            } else {
                CraftBlockchainPlugin.log("makePayment API is down!", Level.SEVERE);
                return CosmWasmTypes.FAILURE;
            }
        }

        return CosmWasmTypes.FAILURE;
    }




    private static PendingTransactions pTxs = PendingTransactions.getInstance();

    public static ErrorTypes transaction(Tx transaction) {
        // int minuteTTL = 30;

        // IF we are in dev mode, don't try to send request to the blockchain, just do the transactions
        if(CraftBlockchainPlugin.getIfInDevMode() == false) {
            // we check how much ucraft is in the transaction data since its on chain, so get the ucraft from the Tx

            // ISSUE: With this logic the user can not redeem from escrow. They just cant sign it anyways so is redundant.
            // try {
            //     long balance = BlockchainRequest.getUCraftBalance(transaction.getToWallet()).get();
            //     if (balance < transaction.getUCraftAmount()) {
            //         CraftBlockchainPlugin.log("Not enough balance for transaction");
            //         return ErrorTypes.NOT_ENOUGH_TO_SEND;
            //     }
            // } catch (Exception e) {
            //     return ErrorTypes.QUERY_ERROR;
            // }
        } else {
            String name = Bukkit.getPlayer(transaction.getFromUUID()).getName().toUpperCase();
            Util.coloredBroadcast("&cDEV MODE IS ENABLED FOR THIS TRANSACTION "+name+" (config.yml, no blockchain request)");
        }

        org.json.JSONObject jsonObject;
        try {
            // we submit the ucraft amount -> the redis for the webapp to sign it directly
            String transactionJson = generateTxJSON(transaction);
            jsonObject = new org.json.JSONObject(transactionJson);
       }catch (JSONException err) {
            CraftBlockchainPlugin.log("EBlockchainRequest.java Error " + err.toString());
            CraftBlockchainPlugin.log("Description: " + transaction.getDescription());
            return ErrorTypes.JSON_PARSE_TRANSACTION;
       }

        pTxs.addPending(transaction.getTxID(), transaction);
        redisDB.submitTxForSigning(transaction.getFromWallet(), transaction.getTxID(), jsonObject.toString(), transaction.getRedisMinuteTTL());

        return ErrorTypes.SUCCESS;
    }

    // private static String tokenDenom = blockchainPlugin.getTokenDenom(true);

    /**
     * Generates a JSON object for a transaction used by the blockchain
     * @param FROM
     * @param TO
     * @param AMOUNT
     * @param DESCRIPTION
     * @return String JSON Amino (Readable by webapp)
     */
    // private static String generateTxJSON(String FROM, String TO, long UCRAFT_AMOUNT, String DESCRIPTION, TransactionType txType) {
    private static String generateTxJSON(Tx tx) {
        long now = Instant.now().getEpochSecond();

        String FROM = tx.getFromWallet();
        String TO = tx.getToWallet();
        String DESCRIPTION = tx.getDescription();
        TransactionType txType = tx.getTxType();

        long UCRAFT_AMOUNT = tx.getUCraftAmount();
        long ucraft_tax_amount = tx.getTotalTaxAmount();

        // EX: {"amount":"2","description":"Purchase Business License for 2","to_address":"osmo10r39fueph9fq7a6lgswu4zdsg8t3gxlqyhl56p","tax":{"amount":0.1,"address":"osmo10r39fueph9fq7a6lgswu4zdsg8t3gxlqyhl56p"},"denom":"uosmo","from_address":"osmo10r39fueph9fq7a6lgswu4zdsg8t3gxlqyhl56p"}

        // Tax is another message done via webapp to pay a fee to the DAO. So the total transaction cost = amount + tax.amount
        // String json = "{\"from_address\": "+FROM+",\"to_address\": "+TO+",\"description\": "+DESCRIPTION+",\"tx_type\": "+txType.toString()+",\"server_name\": "+CraftBlockchainPlugin.SERVER_NAME+",\"timestamp\": "+now+",\"amount\": \""+UCRAFT_AMOUNT+"\",\"denom\": \"ucraft\",\"tax\": { \"amount\": "+ucraft_tax_amount+", \"address\": "+DAO_TAX_WALLET+"}}";
        String json = "{\"from_address\": "+FROM+",\"to_address\": "+TO+",\"description\": "+DESCRIPTION+",\"tx_type\": "+txType.toString()+",\"server_name\": "+CraftBlockchainPlugin.SERVER_NAME+",\"timestamp\": "+now+",\"amount\": \""+UCRAFT_AMOUNT+"\",\"denom\": "+blockchainPlugin.getTokenDenom()+",\"tax\": { \"amount\": "+ucraft_tax_amount+", \"address\": "+DAO_TAX_WALLET+"}}";

        // Escrow, Authentication types = No tax.
        switch (txType) {
            // case COSMWASM: // TODO:
            case AUTHENTICATION:
            case ESCROW_DEPOSIT:
            case ESCROW_WITHDRAW:
            case LIQUIDITY_POOL: // swaps
                json = "{\"from_address\": "+FROM+",\"to_address\": "+TO+",\"description\": "+DESCRIPTION+",\"tx_type\": "+txType.toString()+",\"server_name\": "+CraftBlockchainPlugin.SERVER_NAME+",\"timestamp\": "+now+",\"amount\": \""+UCRAFT_AMOUNT+"\",\"denom\": "+blockchainPlugin.getTokenDenom()+"";
                break;
            default:
                break;
        }
        // CraftBlockchainPlugin.log(v);
        return json;
    }
}
