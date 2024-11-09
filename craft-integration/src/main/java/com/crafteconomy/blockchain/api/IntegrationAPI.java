package com.crafteconomy.blockchain.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.crafteconomy.blockchain.CraftBlockchainPlugin;
import com.crafteconomy.blockchain.core.request.BlockchainRequest;
import com.crafteconomy.blockchain.core.request.EndpointQuery;
import com.crafteconomy.blockchain.core.types.ErrorTypes;
import com.crafteconomy.blockchain.core.types.FaucetTypes;
import com.crafteconomy.blockchain.escrow.EscrowErrors;
import com.crafteconomy.blockchain.escrow.EscrowManager;
import com.crafteconomy.blockchain.transactions.PendingTransactions;
import com.crafteconomy.blockchain.transactions.Tx;
import com.crafteconomy.blockchain.utils.Util;
import com.crafteconomy.blockchain.wallets.WalletManager;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class IntegrationAPI {

    WalletManager walletManager = WalletManager.getInstance();

    private CraftBlockchainPlugin blockchainPlugin;

    // singleton, sets wallet to the server wallet in config
    private final String SERVER_WALLET, ESCROW_WALLET_REST_API;
    private final String webappAddress;
    private final String SERVER_NAME;
    private final String TOKEN_DENOM_NAME;
    private final String TOKEN_DENOM;

    private IntegrationAPI() {
        blockchainPlugin = CraftBlockchainPlugin.getInstance();

        SERVER_WALLET = blockchainPlugin.getServerDaoTaxWallet(); // main wallet used for taxes -> the dow directly.
        if(SERVER_WALLET == null) {
            throw new IllegalStateException("SERVER_WALLET_ADDRESS is not set in config.yml");
        }

        ESCROW_WALLET_REST_API = blockchainPlugin.getServerEscrowRestApiWalletAddress(); // main wallet used for taxes -> the dow directly.
        if(ESCROW_WALLET_REST_API == null) {
            throw new IllegalStateException("ESCROW_WALLET_REST_API wallet is not set in config.yml");
        }


        webappAddress = blockchainPlugin.getConfig().getString("SIGNING_WEBAPP_LINK");
        if(webappAddress == null) {
            throw new IllegalStateException("SIGNING_WEBAPP_LINK is not set in config.yml");
        }

        TOKEN_DENOM_NAME = blockchainPlugin.getTokenDenomName();
        if(TOKEN_DENOM_NAME == null) {
            throw new IllegalStateException("TOKEN_DENOM_NAME is not set in main file.");
        }

        TOKEN_DENOM = blockchainPlugin.getTokenDenom();
        if(TOKEN_DENOM == null) {
            throw new IllegalStateException("TOKEN_DENOM is not set in main file.");
        }

        SERVER_NAME = CraftBlockchainPlugin.SERVER_NAME;
    }

    /**
     * Gets the server wallet, used for paying the server for transactions (ex. taxes) = The DAO multisig
     * @return String Wallet
     */
    public String getServerWallet() {
        return SERVER_WALLET;
    }

    /**
     * Gets the name of the server, ex: economy-1 or hub-1 (depends on the name of the folder)
     * @return String server_name (parent folder)
     */
    public String getServerName() {
        return SERVER_NAME;
    }

    public String getTokenName() {
        return TOKEN_DENOM_NAME;
    }

    public String getTokenDenom() {
        return TOKEN_DENOM_NAME;
    }

    /**
     * Gets the escrow wallet which holds & pays escrows (controlled by the DAO, but is not the DAO fund / multisig.)
     * http://api.crafteconomy.io/v1/dao/escrow_account_info
     *
     * @return String Wallet
     */
    public String getServerEscrowRestApiWallet() {
        return ESCROW_WALLET_REST_API;
    }

    /**
     * Gets where the user will actually be signing the transaction.
     * @return String Wallet
     */
    public String getWebAppAddress() {
        return webappAddress;
    }

    /**
     * Gets a players String wallet address (starts with 'prefix' & is X char long)
     * @param player_uuid
     * @return
     */
    public String getWallet(UUID player_uuid) {
        return walletManager.getAddress(player_uuid);
    }

    /**
     * Sets a players wallet if True, False is an an incorrect wallet address
     * @param player_uuid
     * @return
     */
    public boolean setWallet(UUID player_uuid, String craftWallet) {
        if(isValidWallet(craftWallet)) {
            walletManager.setAddress(player_uuid, craftWallet);
            return true;
        }
        return false;
    }

    /**
     * Checks if a wallet is valid
     * @param wallet
     * @return True if valid, False if incorrect
     */
    public boolean isValidWallet(String address) {
        return WalletManager.isValidWallet(address);
    }

    /**
     * Checks if a player has a wallet set in the database / cache
     * @param player_uuid
     * @return
     */
    public boolean hasAccount(UUID player_uuid) {
        return walletManager.getAddress(player_uuid) != null;
    }

    /**
     * Best to be used for actual transactions since we don't like floats
     * @param craft_amount
     * @return
     */
    public long convertCraftToUCRAFT(float human_amount) {
        return (long)human_amount * 1_000_000;
    }

    /**
     * Best to be used for displaying only
     * @return Float of CRAFT amount to be human readable
     */
    public float convertUCRAFTtoBeReadable(long token){
        return ((float)token / 1_000_000);
    }

    /**
     * Gets the balance of a player based on their wallet address
     * @param player_uuid
     * @return
     */
    public CompletableFuture<Long> getUCraftBalance(UUID player_uuid) {
        String walletAddr = getWallet(player_uuid);
        if(walletAddr == null) {
            return CompletableFuture.completedFuture((long) ErrorTypes.NO_WALLET.code);
        }
        return BlockchainRequest.getUCraftBalance(walletAddr);
    }

    /**
     * Gets the token balance of a player based on their wallet address
     * @param player_uuid
     * @return
     */
    public CompletableFuture<Float> getCraftBalance(UUID player_uuid) {
        // return getUCraftBalance(player_uuid) / 1_000_000;
        return getUCraftBalance(player_uuid).thenApply(token -> convertUCRAFTtoBeReadable(token));
    }

    /**
     * Gets the tax rate of the server (ex. 0.05 = 5% rate * any transaction amount (so 105% total)).
     * Is done via the webapp for you
     * @return
     */
    public Double getTaxRate() {
        return blockchainPlugin.getTaxRate();
    }


    /**
     * Expires a transaction & runs that logic if the plugin wants to do this earlier than intended.
     * Ex: useful for minigames if the user does not sign before the game starts, expire the transaction
     * @param transaction_uuid
     * @return true if it was expired, false if the key was not found
     */
    public boolean expireTransaction(UUID transaction_uuid) {
        // clear the key from the redis cache &
        Tx tx = PendingTransactions.getInstance().getTxFromID(transaction_uuid);
        if(tx == null) {
            CraftBlockchainPlugin.log("[API, expireTransaction] TxID " + transaction_uuid + " is not in pending transactions on this server. Cannot expire it.");
            return false;
        }

        // expire the redis cache key from redismanager
        return PendingTransactions.getInstance().expireTransaction(transaction_uuid);
    }

    /**
     * Clears a users wallet from all pending transactions in game AND in the redis server.
     * This used to be done via the RestAPI, but can now be done on a per user basis as it is more secure.
     * @param wallet_address
     */
    public void clearUsersPendingTxs(String wallet_address) {
        PendingTransactions.clearTransactionsFromWallet(wallet_address);
    }

    /**
     * Send Tokens to another player/wallet. Blockchain integration will run the callback
     * @param from_uuid     Who it is from
     * @param to_wallet     Who to send the tokens too
     * @param amount        Amount of token to send
     * @param description   What this transaction is for
     * @param callback      Function to run for the sender once completed
     * @return Tx
     */
    public Tx createNewTx(UUID playerUUID, @NotNull String to_wallet, float craftAmount, String description, Consumer<UUID> callback) {
        Tx tx = new Tx();
        tx.setFromUUID(playerUUID);
        tx.setToWallet(to_wallet);
        tx.setCraftAmount(craftAmount);
        tx.setDescription(description);
        tx.setFunction(callback);
        return tx;
    }

    /**
     * Allows for 2 players to be involved in a transaction, useful for trading between players
     * @param playerUUID
     * @param recipientUUID
     * @param to_wallet
     * @param amount
     * @param description
     * @param callback
     * @return Tx
     */
    public Tx createNewTx(UUID playerUUID, UUID recipientUUID, @NotNull String to_wallet, float craftAmount, String description, BiConsumer<UUID, UUID> biCallback) {
        Tx tx = new Tx();
        tx.setFromUUID(playerUUID);
        tx.setToUUID(recipientUUID);
        tx.setToWallet(to_wallet);
        tx.setCraftAmount(craftAmount);
        tx.setDescription(description);
        tx.setBiFunction(biCallback);
        return tx;
    }

    /**
     * Creates a transaction which pays tokens back to the servers main wallet
     * @param from_uuid     Who it is from
     * @param amount        Amount of token to send
     * @param description   What this transaction is for
     * @param callback      Function to run for the sender once completed
     * @return              The Transaction (Tx) object
     */
    public Tx createServerTx(UUID from_uuid, long amount, String description, Consumer<UUID> callback) {
        return createNewTx(from_uuid, SERVER_WALLET, amount, description, callback);
    }

    /**
     * Submits a transaction message to the redis instance (To get signed from webapp)
     * @param tx    Transaction to submit
     * @return      The ErrorTypes of the transaction status
     */
    public ErrorTypes submit(Tx tx) { // TTL is done within the Tx itself
        return BlockchainRequest.transaction(tx);
    }

    /**
     * Gives a wallet some tokens (utoken)
     * @param wallet_address
     * @param amount
     * @return  CompletableFuture<FaucetTypes>
     */
    public CompletableFuture<FaucetTypes> faucetUCraft(String wallet_address, String description, long utoken) {
        return BlockchainRequest.depositUCraftToAddress(wallet_address, description, utoken);
    }

    /**
     * Gives a wallet some tokens
     * @param consoleSender
     * @param player_uuid
     * @param amount
     * @return CompletableFuture<FaucetTypes>
     */
    public CompletableFuture<FaucetTypes> faucetCraft(String wallet_address, String description, float craft_amount) {
        return faucetUCraft(wallet_address, description, (long) (craft_amount*1_000_000));
    }

    public CompletableFuture<FaucetTypes> faucetUCraft(UUID uuid, String description, long utoken) {
        return BlockchainRequest.depositUCraftToAddress(walletManager.getAddress(uuid), description, utoken);
    }

    public CompletableFuture<FaucetTypes> faucetCraft(UUID uuid, String description, float craft_amount) {
        return faucetUCraft(uuid, description, (long) (craft_amount*1_000_000));
    }

    // --------------------------------------------------
    // clickable links / commands / TxId's to make user life better
    public void sendWebappForSigning(CommandSender sender, String message, String hoverMsg) {
		Util.clickableWebsite(sender,
            getWebAppAddress(), // link which we have the webapp redirect to
            message,
            hoverMsg
        );
	}
    public void sendWebappForSigning(CommandSender sender, String message) {
        sendWebappForSigning(sender,
            message,
            "&7&oSign your transaction(s) with the KEPLR wallet"
        );
	}
    public void sendWebappForSigning(CommandSender sender) {
        sendWebappForSigning(sender,
            "&6&l[!] &e&nClick here to sign your transaction(s)",
            "&7&oSign your transaction(s) with the KEPLR wallet"
        );
	}
    public void sendWebappForSigning(Player player) {
        sendWebappForSigning((CommandSender)player);
	}


	public void sendClickableKeplrInstallDocs(CommandSender sender) {
		Util.clickableWebsite(sender, "https://docs.crafteconomy.io/set-up/wallet",
            "&2[!] &a&nClick here to learn how to set up your wallet.",
            "&7&oSetup your CRAFT wallet with Keplr"
        );
	}

    public void sendTxIDClickable(CommandSender sender, String TxID, String format, String hoverMessage) {
		Util.clickableCopy(sender, TxID, format, hoverMessage);
	}

    public void sendTxIDClickable(CommandSender sender, String TxID, String format) {
		Util.clickableCopy(sender, TxID, format, "&7&oClick to copy TxID");
	}

	public void sendTxIDClickable(CommandSender sender, String TxID) {
		sendTxIDClickable(sender, TxID, "&7&oTxID: &n%value%");
	}

    public void sendTxIDClickable(Player player, String TxID) {
		sendTxIDClickable((CommandSender)player, TxID, "&7&oTxID: &n%value%");
	}


    public void sendWalletClickable(CommandSender sender, String wallet, String format, String hoverMessage) {
		Util.clickableCopy(sender, wallet, format, hoverMessage);
	}

    public void sendWalletClickable(CommandSender sender, String wallet, String format) {
		sendWalletClickable(sender, wallet, format, "&7&oClick to copy TxID");
	}

    public void sendWalletClickable(CommandSender sender, String wallet) {
		sendWalletClickable(sender, wallet, "&7&oWallet: &n%value%");
	}



    // ESCROW ACCOUNTS

    /**
     * Deposits CRAFT into an in game account (Escrow).
     * Each escrow is redeemable for 1x craft, its deposit rate
     */
    public EscrowErrors escrowUCraftDeposit(UUID playerUUID, long ucraft_amount) {
        // creates a Tx to send token to DAO. On sign, player gets escrow balance
        return EscrowManager.getInstance().depositUCraft(playerUUID, ucraft_amount);
    }
    public EscrowErrors escrowCraftDeposit(UUID playerUUID, float craft_amount) {
        // creates a Tx to send token to DAO. On sign, player gets escrow balance
        return escrowUCraftDeposit(playerUUID, (long) (craft_amount * 1_000_000));
    }

    public long escrowUCraftRedeem(UUID playerUUID, long utoken) {
        // If player has enough escrow, their wallet is paid in token & escrow is subtracted
        return EscrowManager.getInstance().redeemUCraft(playerUUID, utoken);
    }
    public long escrowCraftRedeem(UUID playerUUID, float craft_amount) {
        return escrowUCraftRedeem(playerUUID, (long)(craft_amount*1_000_000));
    }

    public EscrowErrors escrowUCraftSpend(UUID playerUUID, long ucraft_cost) {
        // Will remove balance & return Success if they can spend
        return EscrowManager.getInstance().spendUCraft(playerUUID, ucraft_cost);
    }
    public EscrowErrors escrowCraftSpend(UUID playerUUID, float craft_cost) {
        return escrowUCraftSpend(playerUUID, (long) (craft_cost * 1_000_000));
    }

    public EscrowErrors escrowPayPlayerUCraft(UUID from_uuid, UUID to_uuid, long ucraft_cost) {
        return EscrowManager.getInstance().escrowPayPlayerUCraft(from_uuid, to_uuid, ucraft_cost);
    }
    public EscrowErrors escrowPayPlayerCraft(UUID from_uuid, UUID to_uuid, float craft_cost) {
        return EscrowManager.getInstance().escrowPayPlayerCraft(from_uuid, to_uuid, craft_cost);
    }

    public long escrowGetUCraftBalance(UUID uuid) {
        return EscrowManager.getInstance().getUCraftBalance(uuid);
    }
    public float escrowGetCraftBalance(UUID uuid) {
        return EscrowManager.getInstance().getCraftBalance(uuid);
    }



    private static IntegrationAPI instance = null;
    public static IntegrationAPI getInstance() {
        if(instance == null) {
            instance = new IntegrationAPI();
        }
        return instance;
    }
}
