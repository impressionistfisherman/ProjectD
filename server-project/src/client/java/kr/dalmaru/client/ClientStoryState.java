package kr.dalmaru.client;

import java.util.*;
import com.google.gson.*;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientStoryState {
    private static UUID character;
    private static long query,operation,lastAccepted;
    private static boolean waiting;
    private static JsonObject current;
    private static String message="이야기 정보를 확인 중입니다.";
    static void reset(){character=null;query=operation=lastAccepted=0;waiting=false;current=null;message="이야기 정보를 확인 중입니다.";}
    static void selected(UUID id){if(!Objects.equals(character,id)){reset();character=id;}}
    static void queried(long id){query=id;}
    static void pending(long id){operation=id;waiting=true;message="이야기 요청 결과를 확인 중입니다.";}
    static void complete(long id,boolean rejected){complete(id,"{}",rejected);}
    static void complete(long id,String result,boolean rejected){if(id!=operation)return;waiting=false;message=rejected?DalmaruClient.rejectionText(result,"NPC 위치와 완료 조건을 확인하세요."):"이야기 요청을 처리했습니다.";}
    static void accept(DalmaruPayloads.StoryStateResponse response){
        if(!Objects.equals(character,response.characterId())||(response.requestId()!=query&&response.requestId()!=operation)||response.requestId()<lastAccepted)return;
        try{var state=JsonParser.parseString(response.state()).getAsJsonObject();state.get("chapterId").getAsString();state.get("title").getAsString();state.get("combatXP").getAsBigDecimal().intValueExact();state.get("combatGoal").getAsBigDecimal().intValueExact();state.getAsJsonArray("npcs");state.getAsJsonArray("missing");state.get("finished").getAsBoolean();state.get("ready").getAsBoolean();current=state;lastAccepted=response.requestId();}
        catch(IllegalArgumentException|IllegalStateException|ArithmeticException|JsonParseException|NullPointerException error){message="이야기 응답을 확인하지 못했습니다.";}
    }
    static JsonObject current(){return current;}
    static boolean waiting(){return waiting;}
    static String message(){return message;}
}
