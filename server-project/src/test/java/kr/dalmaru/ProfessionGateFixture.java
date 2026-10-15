package kr.dalmaru;

import java.sql.*;
import java.util.UUID;
import kr.dalmaru.account.*;

final class ProfessionGateFixture {
    private ProfessionGateFixture(){}
    static void complete(Connection connection,UUID character,CharacterProfession profession)throws SQLException {
        for(String id:CharacterRepository.professionGateFlags(profession))try(var insert=connection.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,?,'COMPLETED') ON CONFLICT(character_id,quest_id,content_version) DO UPDATE SET state='COMPLETED'")) {
            insert.setObject(1,character);insert.setString(2,id);insert.setString(3,CharacterRepository.PROFESSION_GATE_VERSION);insert.executeUpdate();
        }
    }
}
