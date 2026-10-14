package kr.dalmaru;

import java.sql.*;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.equipment.*;

public final class EnhancementReplayCheck {
    public static void main(String[] args)throws Exception{
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();
        try{
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();
            var character=characters.create(account,session.connectionGeneration(),1,1,"replay_"+account.toString().substring(0,8)).character();
            characters.selectActive(account,session.connectionGeneration(),2,character.characterId());
            sql("INSERT INTO dalmaru.character_wallet(character_id,copper) VALUES(?,1000) ON CONFLICT(character_id) DO UPDATE SET copper=1000",character.characterId());
            sql("INSERT INTO dalmaru.character_materials(character_id,material_id,quantity,binding) VALUES(?,'material.ironstone',10,'CHARACTER')",character.characterId());
            var repository=new EquipmentRepository();var items=repository.listOrCreate(character.characterId());var id=items.getFirst().equipmentId();
            var first=repository.enhance(account,session.connectionGeneration(),11,id);
            var second=repository.enhance(account,session.connectionGeneration(),12,id);
            long copper=value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character.characterId());
            long material=value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'",character.characterId());
            var replay=repository.enhance(account,session.connectionGeneration(),11,id);
            check(replay.replayed()&&replay.success()==first.success()&&replay.resultEnhancement()==first.resultEnhancement()&&replay.resultFailures()==first.resultFailures(),"Original outcome changed");
            check(replay.equipment().enhancement()==second.equipment().enhancement()&&replay.equipment().consecutiveFailures()==second.equipment().consecutiveFailures(),"Latest state missing");
            check(replay.resultEnhancement()!=replay.equipment().enhancement()||replay.resultFailures()!=replay.equipment().consecutiveFailures(),"Fixture did not advance state");
            check(copper==value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character.characterId())&&material==value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'",character.characterId()),"Replay charged again");
            check(value("SELECT count(*) FROM dalmaru.character_enhancement_requests WHERE account_id=?",account)==2,"Replay duplicated journal");
            check(first.receipt()!=null&&replay.receipt().requestId().equals(first.receipt().requestId())&&replay.receipt().eventId().equals(first.receipt().eventId())&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE account_id=?",account)==2,"Legacy adapter duplicated UUID journal/event");
            boolean rejected=false;try{repository.enhance(account,session.connectionGeneration(),11,items.get(1).equipmentId());}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Changed request body accepted");
            System.out.println("DB enhancement original outcome + latest state + no recharge/body reuse PASS");
        }finally{cleanup(account);}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void cleanup(UUID account)throws SQLException{
        try(var c=connection()){
            c.setAutoCommit(false);
            try{
                for(String sql:List.of("UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?","DELETE FROM dalmaru.character_enhancement_requests WHERE account_id=?","DELETE FROM dalmaru.character_equipment WHERE character_id IN(SELECT character_id FROM dalmaru.characters WHERE account_id=?)","DELETE FROM dalmaru.character_wallet WHERE character_id IN(SELECT character_id FROM dalmaru.characters WHERE account_id=?)","DELETE FROM dalmaru.character_materials WHERE character_id IN(SELECT character_id FROM dalmaru.characters WHERE account_id=?)","DELETE FROM dalmaru.character_selection_requests WHERE account_id=?","DELETE FROM dalmaru.character_creation_requests WHERE account_id=?","DELETE FROM dalmaru.character_profiles WHERE character_id IN(SELECT character_id FROM dalmaru.characters WHERE account_id=?)","DELETE FROM dalmaru.characters WHERE account_id=?","DELETE FROM dalmaru.accounts WHERE account_id=?")){
                    try(var s=c.prepareStatement(sql)){s.setObject(1,account);s.executeUpdate();}
                }
                c.commit();
            }catch(SQLException error){c.rollback();throw error;}
        }
    }
    static Connection connection()throws SQLException{return DriverManager.getConnection("jdbc:postgresql://"+System.getenv().getOrDefault("DALMARU_DB_HOST","localhost")+":"+System.getenv().getOrDefault("DALMARU_DB_PORT","15432")+"/oscar_p1_fixture",System.getenv().getOrDefault("DALMARU_DB_USER","postgres"),System.getenv("DALMARU_DB_PASSWORD"));}
    private static void sql(String sql,UUID id)throws SQLException{try(var c=connection();var s=c.prepareStatement(sql)){s.setObject(1,id);s.executeUpdate();}}
    private static long value(String sql,UUID id)throws SQLException{try(var c=connection();var s=c.prepareStatement(sql)){s.setObject(1,id);try(var r=s.executeQuery()){if(!r.next())throw new AssertionError("Missing fixture row");return r.getLong(1);}}}
}
