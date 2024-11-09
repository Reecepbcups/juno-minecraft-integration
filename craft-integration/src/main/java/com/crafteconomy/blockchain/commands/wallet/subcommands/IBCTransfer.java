package com.crafteconomy.blockchain.commands.wallet.subcommands;

import com.crafteconomy.blockchain.api.IntegrationAPI;
import com.crafteconomy.blockchain.commands.SubCommand;
import com.crafteconomy.blockchain.utils.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

public class IBCTransfer implements SubCommand {

    private IntegrationAPI api = IntegrationAPI.getInstance();

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        if(sender instanceof ConsoleCommandSender) {
            Util.colorMsg(sender, "&cYou must be a player to use this command.");
            return;
        }

        Player player = (Player) sender;
        String wallet = api.getWallet(player.getUniqueId());
        if(wallet == null) {
            Util.colorMsg(sender, "&cYou do not have a wallet. Install one:");
            api.sendClickableKeplrInstallDocs(sender);
            return;
        }

        // grab the item in the players hand
        ItemStack item = player.getInventory().getItemInMainHand();
        String itemsBase64 = itemStackArrayToBase64(item);

        // set players main hand to air (burn the item)
        player.getInventory().setItemInMainHand(null);

        System.out.println("base64Item: ->> " + itemsBase64);

        // now take that, and save it to the players 8th inv slot
        ItemStack newItem;
        try {
            newItem = itemStackArrayFromBase64(itemsBase64);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        } catch (IllegalStateException e) {
            e.printStackTrace();
            return;
        }

        player.getInventory().setItem(8, newItem);

        // sends link to the webapp so user can sign all Txs
        api.sendWebappForSigning(sender, wallet, "\n&6&l[!] &e&nClick here to access the webapp for your account\n");

    }

    public static String itemStackArrayToBase64(ItemStack item) throws IllegalStateException {
        try {
            // https://gist.github.com/graywolf336/8153678
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);

            // Write the size of the inventory
            dataOutput.writeInt(1);

            // Save every element in the list
            // for (int i = 0; i < items.length; i++) {
                dataOutput.writeObject(item);
            // }

            // Serialize that array
            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray()).replaceAll("\\R", "").replaceAll("\r", "").replaceAll("\n", "");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save item stacks.", e);
        }
    }

    public static ItemStack itemStackArrayFromBase64(String data) throws IOException {
    	try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack[] items = new ItemStack[dataInput.readInt()];

            // Read the serialized inventory
            for (int i = 0; i < items.length; i++) {
            	items[i] = (ItemStack) dataInput.readObject();
            }
            dataInput.close();
            return items[0];
        } catch (ClassNotFoundException e) {
            throw new IOException("Unable to decode class type.", e);
        }
    }

}
