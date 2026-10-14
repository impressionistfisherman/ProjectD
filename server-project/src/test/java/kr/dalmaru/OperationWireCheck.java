package kr.dalmaru;

import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import kr.dalmaru.network.DalmaruPayloads;
import kr.dalmaru.request.RequestJournal;

public final class OperationWireCheck {
    public static void main(String[] args){
        if(!RequestJournal.definitelyRejected(new java.util.concurrent.CompletionException(new IllegalStateException(new IllegalArgumentException("body"))))||!RequestJournal.definitelyRejected(new java.sql.SQLException("alias","23505")))throw new AssertionError("Definite validation/alias was not rejected");
        if(RequestJournal.definitelyRejected(new java.sql.SQLException("commit connection lost","08006"))||RequestJournal.definitelyRejected(new java.sql.SQLTimeoutException("timeout"))||RequestJournal.definitelyRejected(new IllegalStateException("unknown")))throw new AssertionError("Uncertain outcome was definitively rejected");
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            var request=new DalmaruPayloads.OperationRequest(123,UUID.randomUUID(),3_000_000_000L,UUID.randomUUID(),"enhance_item","{\"equipmentId\":\""+UUID.randomUUID()+"\"}");
            DalmaruPayloads.OperationRequest.CODEC.encode(buffer,request);if(!request.equals(DalmaruPayloads.OperationRequest.CODEC.decode(buffer)))throw new AssertionError("Envelope roundtrip changed");buffer.clear();
            var context=new DalmaruPayloads.RequestContext(request.connectionGeneration(),request.activeCharacterId());DalmaruPayloads.RequestContext.CODEC.encode(buffer,context);if(!context.equals(DalmaruPayloads.RequestContext.CODEC.decode(buffer)))throw new AssertionError("Context roundtrip changed");buffer.clear();
            var receipt=new DalmaruPayloads.OperationReceipt(request.correlationId(),request.requestId(),UUID.randomUUID(),"pending_recovery","{\"success\":true}",true);DalmaruPayloads.OperationReceipt.CODEC.encode(buffer,receipt);if(!receipt.equals(DalmaruPayloads.OperationReceipt.CODEC.decode(buffer)))throw new AssertionError("Receipt roundtrip changed");buffer.clear();
            boolean rejected=false;try{DalmaruPayloads.OperationRequest.CODEC.encode(buffer,new DalmaruPayloads.OperationRequest(1,request.requestId(),1,request.activeCharacterId(),"enhance_item","x".repeat(8193)));}catch(RuntimeException expected){rejected=true;}if(!rejected)throw new AssertionError("Oversize body encoded");
        }finally{buffer.release();}
        System.out.println("Actual operation/context/receipt codecs and body bound PASS");
    }
}
