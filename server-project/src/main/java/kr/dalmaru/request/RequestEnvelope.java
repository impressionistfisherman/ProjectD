package kr.dalmaru.request;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public record RequestEnvelope(UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,JsonObject payload) {
    public RequestEnvelope {
        Objects.requireNonNull(requestId);Objects.requireNonNull(activeCharacterId);Objects.requireNonNull(operation);Objects.requireNonNull(payload);
        if(requestId.equals(new UUID(0,0))||activeCharacterId.equals(new UUID(0,0))||connectionGeneration<1||!operation.matches("[a-z_]{1,64}")||payload.toString().length()>8192)throw new IllegalArgumentException("Invalid request envelope");
        payload=payload.deepCopy();
    }
    @Override public JsonObject payload(){return payload.deepCopy();}
    public String bodyHash(){
        var body=new JsonObject();body.addProperty("connectionGeneration",connectionGeneration);body.addProperty("activeCharacterId",activeCharacterId.toString());body.addProperty("operation",operation);body.add("payload",payload);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalize(body,0).toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static JsonElement normalize(JsonElement element,int depth){
        if(depth>16)throw new IllegalArgumentException("Request payload is too deep");
        if(element.isJsonObject()){
            var result=new JsonObject();element.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->result.add(entry.getKey(),normalize(entry.getValue(),depth+1)));return result;
        }
        if(element.isJsonArray()){var result=new JsonArray();for(var entry:element.getAsJsonArray())result.add(normalize(entry,depth+1));return result;}
        if(element.isJsonPrimitive()&&element.getAsJsonPrimitive().isNumber())return new JsonPrimitive(new BigDecimal(element.getAsString()).stripTrailingZeros());
        return element.deepCopy();
    }
    public static RequestEnvelope legacy(UUID account,long generation,UUID character,String operation,long request,JsonObject payload){
        if(request<1)throw new IllegalArgumentException("Invalid legacy request ID");
        var uuid=UUID.nameUUIDFromBytes((account+":"+generation+":"+operation+":"+request).getBytes(StandardCharsets.UTF_8));
        return new RequestEnvelope(uuid,generation,character,operation,payload);
    }
}
