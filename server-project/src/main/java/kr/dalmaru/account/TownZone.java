package kr.dalmaru.account;
public record TownZone(String dimension,int x,int y,int z,int radius){
    public boolean contains(String dimension,int x,int y,int z){return this.dimension.equals(dimension)&&Math.abs((long)this.x-x)<=radius&&Math.abs((long)this.y-y)<=radius&&Math.abs((long)this.z-z)<=radius;}
}
