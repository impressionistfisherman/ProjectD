package kr.dalmaru.account;
public record TownZone(String dimension,int x,int y,int z,int radius,String waypointId){
    public TownZone{if(dimension==null||dimension.isBlank()||radius<1||radius>128||waypointId==null||waypointId.isBlank()||waypointId.length()>128)throw new IllegalArgumentException("Invalid town boundary or waypoint ID");}
    public TownZone(String dimension,int x,int y,int z,int radius){this(dimension,x,y,z,radius,dimension+":"+x+":"+y+":"+z);}
    public boolean contains(String dimension,int x,int y,int z){return this.dimension.equals(dimension)&&Math.abs((long)this.x-x)<=radius&&Math.abs((long)this.y-y)<=radius&&Math.abs((long)this.z-z)<=radius;}
}
