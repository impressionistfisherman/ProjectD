package kr.dalmaru.client;

import java.util.UUID;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientLoadoutState {
    private static DalmaruPayloads.LoadoutResponse current;
    private static UUID character;
    private static long request=1,pendingUntil;
    private static boolean waiting;
    static void reset(){current=null;character=null;request=1;waiting=false;}
    static void selected(UUID selected){character=selected;current=null;waiting=false;}
    static void accept(DalmaruPayloads.LoadoutResponse response){
        if(character!=null&&!character.equals(response.characterId()))return;
        if(current!=null&&response.revision()<current.revision())return;
        character=response.characterId();current=response;waiting=false;
    }
    static DalmaruPayloads.LoadoutResponse current(){return current;}
    static long next(){return request++;}
    static boolean waiting(){if(waiting&&System.nanoTime()>=pendingUntil)waiting=false;return waiting;}
    static void pending(){waiting=true;pendingUntil=System.nanoTime()+10_000_000_000L;}
}
