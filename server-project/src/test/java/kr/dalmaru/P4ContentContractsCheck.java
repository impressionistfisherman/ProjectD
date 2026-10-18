package kr.dalmaru;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.RunMechanicAccess;

public final class P4ContentContractsCheck {
    public static void main(String[] args) {
        var current = ContentCatalog.current();
        var mechanics = current.mechanics();
        var b = mechanics.brazier(); var boss = mechanics.boss();
        check(current.version().equals("pilot-p4-1") && b.channelTicks() == 80 && b.cancelDistance() == .5
                && b.interactionRadius() == 3 && b.overlapTicks() == 20, "Channel contract");
        check(b.counts().equals(List.of(1,2,3)) && b.simultaneous().equals(List.of(1,1,2)), "Cooperation contract");
        check(b.idleTicks() == 400 && b.warningTicks() == 200 && b.pulseTicks() == 60
                && b.idleTicks()+b.warningTicks()+b.pulseTicks() == 660 && b.maximumHealthFraction() == .15, "First damage at 33 seconds");
        check(boss.gatekeeperPosture() == 650 && boss.beaconPosture() == 900 && boss.wallPostureDamage() == 225
                && boss.postureScales().equals(List.of(1.,1.7,2.8)) && boss.trainingPostureScale() == 1.2
                && boss.risingStrikePosture().equals(List.of(18.,30.)) && boss.collapseTicks() == 80 && boss.collapsedDamageScale() == 1.3 && boss.immunityTicks() == 300
                && mechanics.minimumTelegraphTicks() == 20, "Boss contract");
        var archived = root(current); archived.getAsJsonObject("mechanics").getAsJsonObject("brazier").addProperty("channelTicks",100);
        check(ContentCatalog.parse(archived.toString()).mechanics().brazier().channelTicks() == 100
                && current.mechanics().brazier().channelTicks() == 80, "Frozen mechanics overwritten");
        var legacy = root(current); legacy.addProperty("schemaVersion",4); legacy.addProperty("contentVersion","pilot-p3-4");
        legacy.remove("mechanics");
        for (var entry : legacy.getAsJsonArray("runs")) {
            entry.getAsJsonObject().addProperty("version","pilot-p3-4");
            entry.getAsJsonObject().addProperty("contentVersion","pilot-p3-4");
        }
        var frozen = ContentCatalog.parse(legacy.toString());
        check(frozen.mechanics() == null && frozen.version().equals("pilot-p3-4")
                && !JsonParser.parseString(frozen.snapshot()).getAsJsonObject().has("mechanics"), "Legacy snapshot stamped with P4");
        reject(current, r -> r.remove("mechanics"));
        reject(current, r -> r.addProperty("schemaVersion",4));
        reject(current, r -> r.getAsJsonObject("mechanics").addProperty("temporary",false));
        reject(current, r -> r.getAsJsonObject("mechanics").addProperty("minimumTelegraphTicks",19));
        for (String key : List.of("channelTicks","idleTicks","warningTicks","pulseTicks","overlapTicks"))
            reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("brazier").addProperty(key,0));
        reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("brazier").addProperty("channelTicks",80.5));
        reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("brazier").addProperty("maximumHealthFraction",1.01));
        reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("brazier").getAsJsonArray("simultaneous").set(0,new JsonPrimitive(2)));
        reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("boss").addProperty("collapsedDamageScale",.9));
        reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("boss").getAsJsonArray("risingStrikePosture").remove(1));
        reject(current, r -> r.getAsJsonObject("mechanics").getAsJsonObject("boss").getAsJsonArray("postureScales").set(0,new JsonPrimitive(0)));
        try { b.counts().clear(); throw new AssertionError("Mutable tiers"); } catch (UnsupportedOperationException expected) {}
        var views = new ArrayList<RunMechanicAccess.BrazierView>();
        var snapshot = new RunMechanicAccess.Snapshot(UUID.randomUUID(),1,RunMechanicAccess.Phase.BRAZIER,1,
                views,RunMechanicAccess.Neglect.IDLE,400,List.of());
        views.add(new RunMechanicAccess.BrazierView(net.minecraft.core.BlockPos.ZERO,false,null,0));
        check(snapshot.braziers().isEmpty(), "Mutable server snapshot");
        System.out.println("P4 frozen mechanics, legacy P3 preservation, invalid definitions and immutable views PASS");
    }
    private static JsonObject root(ContentCatalog c) { return JsonParser.parseString(c.snapshot()).getAsJsonObject(); }
    private static void reject(ContentCatalog c, Consumer<JsonObject> mutation) {
        var r=root(c); mutation.accept(r);
        try { ContentCatalog.parse(r.toString()); throw new AssertionError("Invalid P4 accepted"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
