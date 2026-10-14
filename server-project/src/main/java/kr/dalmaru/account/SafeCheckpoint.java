package kr.dalmaru.account;
public record SafeCheckpoint(String pointId,String dimension,double x,double y,double z){
    public SafeCheckpoint{if(pointId.isBlank()||dimension.isBlank()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid safety checkpoint.");}
}
