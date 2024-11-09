import { redisClient, collections } from './database.service';
import { sendDiscordWebhook } from './discord.service';

// https://cosmos.github.io/cosmjs/
import { assertIsDeliverTxSuccess, calculateFee, GasPrice, SigningStargateClient, StargateClient, QueryClient, DeliverTxResponse } from "@cosmjs/stargate";
import { DirectSecp256k1HdWallet } from "@cosmjs/proto-signing";
import { coin, coins } from "@cosmjs/amino";
import { fromBech32 } from "@cosmjs/encoding";

// Env
import { config } from 'dotenv';
config();

const WALLET_PREFIX = `${process.env.CRAFT_WALLET_PREFIX}` || "craft";
const DENOM = `${process.env.CRAFT_DENOM}` || "ucraft";
const DENOM_NAME = `${process.env.CRAFT_DENOM_NAME}` || "ucraft";
const GAS_PRICES = `${process.env.CRAFT_GAS_PRICES}` || "0.0025"
const BASE_GAS = Number(`${process.env.CRAFT_BASE_GAS}`) || 75_000
const PER_MSG_GAS = Number(`${process.env.CRAFT_PER_MSG_GAS}`) || 50_000

// == testnet ==
// const DENOM = "ujunox";
// const DENOM_NAME = "junox";
// const GAS_PRICES = 0.0025
// const BASE_GAS = 75_000
// const PER_MSG_GAS = 50_000

// == mainnet ==
// const DENOM = "ujuno";
// const DENOM_NAME = "juno";
// const GAS_PRICES = 0.075
// const BASE_GAS = 100_000
// const PER_MSG_GAS = 50_000



// create boolean to disable caching
const allowCache = false;

// https://github.com/cosmos/cosmjs/tree/main/packages/cli/examples


export const getEscrowBalances = async () => {
    const REDIS_KEY = `cache:escrow_balances`;
    if(allowCache) {
        let escrow_balances = await redisClient?.get(REDIS_KEY);
        if (escrow_balances) {
            // console.log(`BuildingName found in redis cache -> ${cachedBuildingName} from ${buildingId}. Not calling MongoDB`);
            return escrow_balances;
        }
    }

    // print all collections?.escrow
    // const v = await collections?.escrow?.find({}).toArray();
    // console.log(v);

    // sum all the values in the collections?.escrow? collection as ucraft
    const escrow_balances_data = await collections?.escrow?.aggregate([
        {
            $group: {
                _id: null,
                total: { $sum: "$ucraft_amount" },
                unique_records: { $sum: 1 },
            },
        },
    ]).toArray();


    let data = {
        balances: 0,
        denom: DENOM_NAME,
        unique_accounts: 0,
    };
    if(escrow_balances_data && escrow_balances_data.length >= 1) {
        let { total, unique_records } = escrow_balances_data[0];
        // console.log(total, unique_records);

        return {
            balances: total,
            denom: DENOM,
            unique_accounts: unique_records,
        };
    }

    // save total_escrow_balances to redis
    if(allowCache) {
        await redisClient?.setEx(REDIS_KEY, 60*10, JSON.stringify(data)); // 10 min cache
    }
    return data;
}

export const getServersEscrowAccountInfo = async () => {
    const walletMnumonic = `${process.env.CRAFT_DAO_ESCROW_WALLET_MNUMONIC}`
    let data_format = {
        address: "",
        denom: "",
        balance: -1,
        held_escrows: await getEscrowBalances(),
        // error: "CRAFT_DAO_ESCROW_WALLET_MNUMONIC variable was not set correctly."
    }

    if (walletMnumonic.split(" ").length < 12) {
        return data_format
    }

    const wallet = await DirectSecp256k1HdWallet.fromMnemonic(walletMnumonic, { prefix: WALLET_PREFIX });
    const [account] = await wallet.getAccounts();
    const balance = await getCraftBalance(account.address);

    data_format.address = account.address;
    data_format.denom = balance.denom;
    data_format.balance = Number(balance.amount);
    // return {
    //     address: `${account.address}`,
    //     denom: balance.denom,
    //     balance: balance.amount,
    //     escrows: escrow_amt,
    // };
    return data_format;
}

// escrow account
export const getCraftBalance = async (wallet_addr) => {
    // get escrow account
    let balance = coin("0", DENOM);
    try {
        const client = await SigningStargateClient.connectWithSigner(`${process.env.CRAFTD_NODE}`, wallet_addr);
        // const balance = await client.getAllBalances(account.address)
        balance = await client.getBalance(wallet_addr, DENOM)
    } catch (error) {
        console.log("getCraftBalance", error);
    }

    return balance;
}

export const getWallets = async () => {
    let DAO_ADDRS = `${process.env.DAO_WALLETS}`
    const addresses: string[] = [];
    for (const addr of DAO_ADDRS.split(",")) {
        addresses.push(addr);
    }
    // console.log(addresses);
    return addresses;
}

const getWalletAPrefix = (address: string) => {
    const decoded = fromBech32(address);
    return decoded.prefix;
}


// an  array of objects which are of type Any
import { EncodeObject } from "@cosmjs/proto-signing";
let messages: EncodeObject[] = []

const assetProperSecret = (secret: string): boolean => {
    if (secret !== process.env.CRAFT_DAO_ESCROW_SECRET) {
        console.log("Secret passed through function: " + secret)
        return false;
    }

    return true
}

export const addBundlePayment = async (secret: string, recipient_wallet: string, utoken_amount: string) => {
    if(assetProperSecret(secret) === false) {
        return {"error": "secret is incorrect"};
    }

    const fromAcc = await getServersEscrowAccountInfo();

    const msg: EncodeObject = {
        typeUrl: "/cosmos.bank.v1beta1.MsgSend",
        value: {
            fromAddress: fromAcc.address,
            toAddress: recipient_wallet,
            amount: [
                {
                    denom: DENOM,
                    amount: utoken_amount,
                },
            ],
        },
    }
    messages.push(msg);

    return { "success": { "wallet": recipient_wallet, "utoken_amount": utoken_amount } };
}

export const performItemIBCTransfer = async (secret: string, recipient_wallet: string, item: string) => {
    if(assetProperSecret(secret) === false) {
        return {"error": "secret is incorrect"};
    }

    const fromAcc = await getServersEscrowAccountInfo();

    // https://github.com/cosmos/cosmjs/blob/e819a1fc0e99a3e5320d8d6667a08d3b92e5e836/packages/cosmwasm-stargate/src/modules/wasm/aminomessages.ts#L87
    const msg: EncodeObject = {
        typeUrl: "/cosmwasm.wasm.v1.MsgExecuteContract",
        value: {
            sender: fromAcc.address,
            contract: "cosmos14hj2tavq8fpesdwxxcu44rty3hh90vhujrvcmstl4zr3txmfvw9s4hmalr",
            msg: {
                transfer: {
                    channel: "wasm.cosmos14hj2tavq8fpesdwxxcu44rty3hh90vhujrvcmstl4zr3txmfvw9s4hmalr",
                    wallet: recipient_wallet,
                    item: item,
                }
            },
            funds: [],
        },
    }
    // TODO: may just have to run it here vs the bundler
    messages.push(msg);

    return { "success": { "wallet": recipient_wallet, "item": item } };
}

export const getBundledMessages = () => {
    return messages;
}

// add a way to sign this and submit
export const signAndBroadcastBundlePayment = async (secret: string) => {
    // TODO: Ensure if height was already sent, we dont try to call again.
    if(assetProperSecret(secret) === false) {
        return {"error": "secret is incorrect"};
    }

    let client: SigningStargateClient;
    let account;
    try {
        // TODO: pre generate these so we can just grab the client & sign?
        const server_wallet = await DirectSecp256k1HdWallet.fromMnemonic(`${process.env.CRAFT_DAO_ESCROW_WALLET_MNUMONIC}`, { prefix: WALLET_PREFIX });
        client = await SigningStargateClient.connectWithSigner(`${process.env.CRAFTD_NODE}`, server_wallet);
        account = await server_wallet.getAccounts();
    } catch (error) {
        console.log(error);
        return;
    }

    // print account address
    console.log(account[0].address);

    let result: DeliverTxResponse;

    // const coins_amt = coins(ucraft_amount, DENOM);
    const gasPrice = GasPrice.fromString(GAS_PRICES.toString() + DENOM);

    const gasCost = BASE_GAS + (messages.length * PER_MSG_GAS)
    const fee = calculateFee(gasCost, gasPrice);

    const memo = "(https://craft-app.reece.sh) Payment from MINECRAFT SERVER @ " + new Date().toISOString() + " with payments: " + messages.length;
    console.log(memo)

    let allAccounts: string[] = [];
    try {
        console.log("DEBUG: " + account[0].address + " sending " + messages + " fee: " + fee);
        result = await client.signAndBroadcast(account[0].address, messages, fee, memo);

        // assertIsDeliverTxSuccess(result);
        for (const msg of messages) {
            allAccounts.push(msg.value.to_address);
        }

        // clear messages
        messages = [];

        console.log("Successfully broadcasted bulk Txs:", result.code, result.height, result.transactionHash);
    } catch (err) {
        // {"error":"{\"code\":-32603,\"message\":\"Internal error\",\"data\":\"tx already exists in cache\"}"}
        // TODO: save to DB to retry later

        let code: string = "unknown"
        let reason: string = err.message;
        if (err.message.includes("Code: ")) {
            code = err.message.split("Code: ");
            code = code[1].split(";")[0];
            reason = err.message.split("message index: ")[1]//.split("\"")[0];
        } else {
            console.log("Error:", err.message);
        }

        const hasEnoughFunds = !err.message.includes("insufficient funds");
        return { "error": { "code": code, "reason": reason, "hasEnoughFunds": hasEnoughFunds } };
    }


    const res = {"success": { "transactionHash": result.transactionHash, "height": result.height, "payments": messages.length, "accounts": allAccounts } };

    // TODO: Push allAccounts to redis so we can subscribe to it in game and announce to players in the list as they get paid every X seconds.

    // return an array here of all sends / interacts that are NOT smart contracts?
    return res;
}


/**
 * https://github.com/cosmos/cosmjs/blob/main/packages/cli/examples/local_faucet.ts
 *
 * This function will pay a player's account from their esgrow wallet in game.
 *
 * curl --data '{"secret": "7821719493", "description": "test description", "wallet": "craft10r39fueph9fq7a6lgswu4zdsg8t3gxlqd6lnf0", "ucraft_amount": 500}' -X POST -H "Content-Type: application/json"  http://localhost:4000/v1/dao/make_payment
*/
// deprecated in favor of bulk updates
export const makePayment = async (secret: string, recipient_wallet: string, ucraft_amount: string, description: string) => {
    // confirm request amount not > DAO wallet balance. If so return error & dont process in game
    // TODO: Future: Bulk pay transactions?

    // check if secret & if so, check if == process.env.CRAFT_DAO_ESCROW_SECRET
    if(assetProperSecret(secret) === false) {
        return {"error": "secret is incorrect"};
    }

    let client: SigningStargateClient;
    let account;
    try {
        // TODO: pre generate these so we can just grab the client & sign?
        const server_wallet = await DirectSecp256k1HdWallet.fromMnemonic(`${process.env.CRAFT_DAO_ESCROW_WALLET_MNUMONIC}`, { prefix: WALLET_PREFIX });
        client = await SigningStargateClient.connectWithSigner(`${process.env.CRAFTD_NODE}`, server_wallet);
        account = await server_wallet.getAccounts();
    } catch (error) {
        console.log(error);
        return;
    }

    const time = new Date().toISOString();
    const coins_amt = coins(ucraft_amount, DENOM);
    const gasPrice = GasPrice.fromString(GAS_PRICES.toString() + DENOM);
    const fee = calculateFee(BASE_GAS+PER_MSG_GAS, gasPrice);
    let result;

    try {
        console.log("DEBUG: " + account[0].address + " sending " + coins_amt + " to " + recipient_wallet + " with description " + description + " fee: " + fee);
        result = await client.sendTokens(
            account[0].address,
            recipient_wallet,
            coins_amt,
            fee,
            "Payment from SERVER @ " + time + " " + description
        );

        assertIsDeliverTxSuccess(result);

        // console.log("Successfully broadcasted:", result.code, result.height, result.transactionHash, (result.rawLog).toString());
        console.log("Successfully broadcasted:", result.code, result.height, result.transactionHash);
        // return { "success": { "wallet": recipient_wallet, "ucraft_amount": ucraft_amount, "craft_amount": "999", "serverCraftBalLeft": "999", "transactionHash": result.transactionHash, "height": result.height } };
    } catch (err) {
        // {"error":"{\"code\":-32603,\"message\":\"Internal error\",\"data\":\"tx already exists in cache\"}"}
        // TODO: save to DB to retry later

        let code: string = "unknown"
        let reason: string = err.message;
        if (err.message.includes("Code: ")) {
            code = err.message.split("Code: ");
            code = code[1].split(";")[0];
            reason = err.message.split("message index: ")[1]//.split("\"")[0];
        } else {
            console.log("Error:", err.message);
        }

        const hasEnoughFunds = !err.message.includes("insufficient funds");
        return { "error": { "code": code, "reason": reason, "hasEnoughFunds": hasEnoughFunds } };
    }

    let serverBalanceLeft = await getServersEscrowAccountInfo();
    let balanceLeftString = "";
    if (serverBalanceLeft) {
        balanceLeftString = (Number(serverBalanceLeft.balance) / 1_000_000).toString() + DENOM;
    }

    // bigint ucraft_amount
    let asCraft = BigInt(ucraft_amount) / BigInt(1_000_000);

    let desc = ucraft_amount.toString() + DENOM;
    if(asCraft >= 0.05) {
        desc += " | (" + asCraft.toString() + " " + DENOM + " )";
    }
    await sendDiscordWebhook(
        'SERVER PAYMENT | ' + time,
        desc,
        {
            "Wallet": recipient_wallet,
            "Description": description,
            "Server bal Left: ": balanceLeftString
        },
        '#0099ff'
    );

    return { "success": { "wallet": recipient_wallet, "ucraft_amount": ucraft_amount, "craft_amount": asCraft.toString(), "serverCraftBalLeft": balanceLeftString, "transactionHash": result.transactionHash, "height": result.height } };
};
