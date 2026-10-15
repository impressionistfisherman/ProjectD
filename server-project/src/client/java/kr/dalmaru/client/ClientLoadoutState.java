package kr.dalmaru.client;

import java.util.UUID;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientLoadoutState {
    private static DalmaruPayloads.LoadoutResponse current;
    private static UUID character;
    private static long request=1,pendingUntil,queryUntil,minimumRequest=1,lastAccepted,pendingRequest,recoveryRequest,queryRequest;
    private static boolean waiting,recovering,queryWaiting,delegated;
    static void reset(){current=null;character=null;request=1;minimumRequest=1;lastAccepted=0;pendingRequest=0;recoveryRequest=0;queryRequest=0;waiting=false;recovering=false;queryWaiting=false;}
    static void selected(UUID selected){character=selected;current=null;waiting=false;recovering=false;queryWaiting=false;minimumRequest=request;lastAccepted=0;}
    static void accept(DalmaruPayloads.LoadoutResponse response){
        if(response.requestId()<minimumRequest)return;
        if(character!=null&&!character.equals(response.characterId()))return;
        if(current!=null&&response.revision()<current.revision())return;
        if(current!=null&&response.revision()==current.revision()&&response.requestId()<lastAccepted)return;
        character=response.characterId();current=response;lastAccepted=Math.max(lastAccepted,response.requestId());
        if(response.requestId()==queryRequest)queryWaiting=false;
        if(response.requestId()==pendingRequest||response.requestId()==recoveryRequest){waiting=false;recovering=false;}
    }
    static DalmaruPayloads.LoadoutResponse current(){return current;}
    static long next(){return request++;}
    static boolean waiting(){return waiting;}
    static void pending(long requestId){waiting=true;delegated=false;recovering=false;pendingRequest=requestId;recoveryRequest=0;pendingUntil=System.nanoTime()+10_000_000_000L;}
    static void delegateRetry(){delegated=true;}
    static void completeOperation(long correlation){if(!waiting||correlation!=pendingRequest)return;waiting=false;recovering=false;delegated=false;}
    static boolean tick(){long now=System.nanoTime();if(waiting&&!delegated&&now>=pendingUntil){recovering=true;pendingUntil=now+10_000_000_000L;return true;}if(queryWaiting&&now>=queryUntil){queryUntil=now+10_000_000_000L;return true;}return false;}
    static void queried(long requestId){queryRequest=requestId;queryWaiting=true;queryUntil=System.nanoTime()+10_000_000_000L;if(recovering)recoveryRequest=requestId;}
}
