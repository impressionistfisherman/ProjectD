package kr.dalmaru;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.combat.CombatCalculator;
import kr.dalmaru.combat.CombatUnits;
import kr.dalmaru.equipment.EquipmentRecord;
import kr.dalmaru.equipment.PilotEquipment;

public final class SoloCombatDefinitionCheck {
    public static void main(String[] args) {
        UUID character = new UUID(0, 15);
        var profile = new CharacterProfile(character,15,0,0,40,0,0,20,10,false,0,"WARRIOR");
        List<EquipmentRecord> gear = new ArrayList<>();
        gear.add(item(character,0,"pilot.weapon.two_handed_sword.15",15));
        String[] parts = {"head","body","arms","feet"};
        for(int i=0;i<4;i++) gear.add(item(character,i+2,"pilot.armor.heavy."+parts[i]+".15",15));
        String[] accessories = {"neck","ring","charm"};
        for(int i=0;i<3;i++) gear.add(item(character,i+6,"pilot.accessory."+accessories[i]+".15",15));
        var stats = PilotEquipment.equipped(profile,gear);
        require(stats.attack()==51 && stats.physicalDefense()==75 && stats.magicDefense()==38, "15-level equipment table");
        require(stats.primary()==8 && stats.vitality()==6 && stats.spirit()==4, "equipment budget totals");
        var effective = new CharacterProfile(character,15,0,0,48,0,0,26,14,false,0,"WARRIOR");
        var combat = CombatCalculator.derive(effective,48);
        int hit = CombatCalculator.damage(stats.attack(),combat.strengthMultiplier(),1,false,80);
        require(combat.maximumHealth()==740 && hit==52, "documented solo reference");
        require(Math.ceil(10800.0/hit)==208 && Math.ceil(14400.0/hit)==277, "boss strike counts");
        require(CombatUnits.maximumHealth(10800)==540 && CombatUnits.maximumHealth(14400)==720, "uncapped boss HP");
        require(Math.abs(CombatUnits.toRpg(CombatUnits.maximumHealth(14400*1.38))-19872)<.01, "training HP round trip");
        require(Math.abs(CombatUnits.toRpg(CombatUnits.toEngine(hit))-hit)<.001, "damage round trip");
        require(PilotEquipment.equipped(profile,List.of(item(character,0,"starter.slot.0",1))).attack()==0, "no invented starter attack");
        require(PilotEquipment.equipped(profile,List.of(item(character,0,"pilot.weapon.two_handed_sword.20",20))).attack()==0, "level requirement");
        expectRejected(() -> PilotEquipment.equipped(profile,List.of(item(new UUID(0,99),0,"pilot.weapon.two_handed_sword.15",15))));
        expectRejected(() -> PilotEquipment.equipped(profile,List.of(gear.getFirst(),gear.getFirst())));
        expectRejected(() -> CombatUnits.maximumHealth(20481));
        expectRejected(() -> CombatUnits.toEngine(Double.NaN));
        System.out.println("Solo equipment ownership/budget, RPG units, and 208/277 strike reference verified.");
    }
    private static EquipmentRecord item(UUID owner,int slot,String id,int level) {
        return new EquipmentRecord(UUID.randomUUID(),owner,slot,id,level,"",0,0,true);
    }
    private static void require(boolean condition,String reason) { if(!condition)throw new AssertionError(reason); }
    private static void expectRejected(Runnable operation) {
        try { operation.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input accepted");
    }
}
