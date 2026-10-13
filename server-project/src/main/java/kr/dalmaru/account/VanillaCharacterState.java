package kr.dalmaru.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Keeps the complete item codecs (including components), rather than reducing stacks to item IDs. */
public final class VanillaCharacterState {
    private static final net.fabricmc.fabric.api.attachment.v1.AttachmentType<String> OWNER = net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.<String>create(net.minecraft.resources.Identifier.parse("dalmaru:vanilla_owner"), builder -> builder.persistent(com.mojang.serialization.Codec.STRING).copyOnDeath());
    private VanillaCharacterState() { }
    public static void initialize() { }
    public static void quarantine(ServerPlayer player) {
        player.closeContainer();player.removeAttached(OWNER);
        player.getInventory().clearContent();player.getEnderChestInventory().clearContent();
        player.inventoryMenu.broadcastChanges();
    }
    public static String capture(ServerPlayer player) {
        JsonObject result = new JsonObject();
        String owner = player.getAttached(OWNER);
        if(owner!=null)result.addProperty("owner",owner);
        result.add("inventory", encode(player, player.getInventory()));
        result.add("ender", encode(player, player.getEnderChestInventory()));
        result.addProperty("slot", player.getInventory().getSelectedSlot());
        result.addProperty("level", player.experienceLevel);
        result.addProperty("total", player.totalExperience);
        result.addProperty("progress", player.experienceProgress);
        return result.toString();
    }
    public static void restore(ServerPlayer player, String state) {
        JsonObject json = state == null ? null : JsonParser.parseString(state).getAsJsonObject();
        String owner = json == null || !json.has("owner") ? null : json.get("owner").getAsString();
        if(json!=null&&json.has("empty")&&json.get("empty").getAsBoolean())json=null;
        ItemStack[] inventory = decode(player, player.getInventory(), json == null ? null : json.getAsJsonArray("inventory"));
        ItemStack[] ender = decode(player, player.getEnderChestInventory(), json == null ? null : json.getAsJsonArray("ender"));
        int slot = json == null ? 0 : json.get("slot").getAsInt();
        int level = json == null ? 0 : json.get("level").getAsInt(), total = json == null ? 0 : json.get("total").getAsInt();
        float progress = json == null ? 0 : json.get("progress").getAsFloat();
        if (slot < 0 || slot > 8 || level < 0 || total < 0 || !Float.isFinite(progress) || progress < 0 || progress >= 1) throw new IllegalArgumentException("Invalid character inventory state.");
        player.closeContainer();
        for (int i=0;i<inventory.length;i++) player.getInventory().setItem(i, inventory[i]);
        for (int i=0;i<ender.length;i++) player.getEnderChestInventory().setItem(i, ender[i]);
        player.getInventory().setSelectedSlot(slot);
        player.experienceLevel=level; player.totalExperience=total; player.experienceProgress=progress;
        player.giveExperiencePoints(0);
        player.inventoryMenu.broadcastChanges();
        if(owner==null)player.removeAttached(OWNER);else player.setAttached(OWNER,owner);
    }
    private static JsonArray encode(ServerPlayer player, Container container) {
        JsonArray result = new JsonArray();
        var ops = player.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (int i=0;i<container.getContainerSize();i++) result.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, container.getItem(i)).getOrThrow());
        return result;
    }
    private static ItemStack[] decode(ServerPlayer player, Container container, JsonArray values) {
        if (values != null && values.size()!=container.getContainerSize()) throw new IllegalArgumentException("Inventory size changed.");
        ItemStack[] result = new ItemStack[container.getContainerSize()];
        var ops = player.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (int i=0;i<result.length;i++) result[i]=values==null ? ItemStack.EMPTY : ItemStack.OPTIONAL_CODEC.parse(ops, values.get(i)).getOrThrow();
        return result;
    }
}
