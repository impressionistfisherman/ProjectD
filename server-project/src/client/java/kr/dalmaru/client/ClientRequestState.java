package kr.dalmaru.client;

import java.util.*;
import com.google.gson.JsonObject;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientRequestState {
    private static UUID character;
    private static long generation;
    private static int tick;
    private record Pending(DalmaruPayloads.OperationRequest request,int deadline){}
    private static final Map<UUID,Pending> pending=new HashMap<>();
    static void reset(){character=null;generation=0;tick=0;pending.clear();}
    static void selected(UUID id){character=id;generation=0;pending.clear();}
    static void accept(DalmaruPayloads.RequestContext context){
        if(context.connectionGeneration()<1||context.activeCharacterId().equals(new UUID(0,0))||(character!=null&&!character.equals(context.activeCharacterId()))||context.connectionGeneration()<generation)return;
        if(context.connectionGeneration()>generation)pending.clear();character=context.activeCharacterId();generation=context.connectionGeneration();
    }
    static DalmaruPayloads.OperationRequest begin(long correlation,String operation,JsonObject payload){
        if(generation<1||character==null||correlation<1)return null;
        var request=new DalmaruPayloads.OperationRequest(correlation,UUID.randomUUID(),generation,character,operation,payload.toString());
        pending.put(request.requestId(),new Pending(request,tick+100));return request;
    }
    static List<DalmaruPayloads.OperationRequest> tick(){
        tick++;var retries=new ArrayList<DalmaruPayloads.OperationRequest>();
        for(var entry:pending.entrySet())if(tick>=entry.getValue().deadline()){
            var request=entry.getValue().request();retries.add(request);entry.setValue(new Pending(request,tick+100));
        }
        return retries;
    }
    static DalmaruPayloads.OperationRequest accept(DalmaruPayloads.OperationReceipt response){
        var item=pending.get(response.requestId());
        if(item==null||item.request().correlationId()!=response.correlationId()||item.request().connectionGeneration()!=generation||!item.request().activeCharacterId().equals(character))return null;
        if(!Set.of("rejected","pending_recovery","completed").contains(response.state()))return null;
        if(!response.state().equals("rejected")&&response.eventId().equals(new UUID(0,0)))return null;
        pending.remove(response.requestId());return item.request();
    }
}
